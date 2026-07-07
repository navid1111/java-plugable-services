import crypto from 'node:crypto';
import net from 'node:net';

const [wsUrl, aliceToken, bobToken, chatIdRaw] = process.argv.slice(2);

if (!wsUrl || !aliceToken || !bobToken || !chatIdRaw) {
  console.error('usage: node ws-smoke.mjs ws://host/chat/ws <alice-token> <bob-token> <chat-id>');
  process.exit(2);
}

const chatId = Number(chatIdRaw);

function frameText(payload) {
  const body = Buffer.from(payload);
  const header = [];
  header.push(0x81);
  if (body.length < 126) {
    header.push(0x80 | body.length);
  } else if (body.length < 65536) {
    header.push(0x80 | 126, (body.length >> 8) & 0xff, body.length & 0xff);
  } else {
    throw new Error('payload too large for smoke test');
  }

  const mask = crypto.randomBytes(4);
  const masked = Buffer.alloc(body.length);
  for (let i = 0; i < body.length; i += 1) {
    masked[i] = body[i] ^ mask[i % 4];
  }
  return Buffer.concat([Buffer.from(header), mask, masked]);
}

function readFrame(buffer) {
  if (buffer.length < 2) {
    return null;
  }
  const first = buffer[0];
  const second = buffer[1];
  let length = second & 0x7f;
  let offset = 2;
  if (length === 126) {
    if (buffer.length < 4) {
      return null;
    }
    length = buffer.readUInt16BE(2);
    offset = 4;
  } else if (length === 127) {
    throw new Error('64-bit frames are out of scope for smoke test');
  }
  const masked = (second & 0x80) !== 0;
  const maskOffset = offset;
  if (masked) {
    offset += 4;
  }
  if (buffer.length < offset + length) {
    return null;
  }
  let payload = buffer.subarray(offset, offset + length);
  if (masked) {
    const mask = buffer.subarray(maskOffset, maskOffset + 4);
    payload = Buffer.from(payload.map((byte, index) => byte ^ mask[index % 4]));
  }
  return {
    opcode: first & 0x0f,
    text: payload.toString('utf8'),
    rest: buffer.subarray(offset + length),
  };
}

class RawWebSocket {
  constructor(url, token) {
    this.url = new URL(url);
    this.token = token;
    this.socket = null;
    this.buffer = Buffer.alloc(0);
    this.events = [];
    this.waiters = [];
  }

  connect(expectStatus = 101) {
    return new Promise((resolve, reject) => {
      const port = Number(this.url.port || 80);
      const key = crypto.randomBytes(16).toString('base64');
      const socket = net.createConnection({ host: this.url.hostname, port }, () => {
        const request = [
          `GET ${this.url.pathname}${this.url.search} HTTP/1.1`,
          `Host: ${this.url.host}`,
          'Upgrade: websocket',
          'Connection: Upgrade',
          'Sec-WebSocket-Version: 13',
          `Sec-WebSocket-Key: ${key}`,
          `Authorization: Bearer ${this.token}`,
          '',
          '',
        ].join('\r\n');
        socket.write(request);
      });

      let handshake = Buffer.alloc(0);
      const onHandshakeData = (chunk) => {
        handshake = Buffer.concat([handshake, chunk]);
        const end = handshake.indexOf('\r\n\r\n');
        if (end === -1) {
          return;
        }
        socket.off('data', onHandshakeData);
        const head = handshake.subarray(0, end).toString('utf8');
        const status = Number(head.split('\r\n')[0].split(' ')[1]);
        if (status !== expectStatus) {
          socket.destroy();
          reject(new Error(`expected websocket status ${expectStatus}, got ${status}`));
          return;
        }
        if (expectStatus !== 101) {
          socket.end();
          resolve(status);
          return;
        }

        this.socket = socket;
        const rest = handshake.subarray(end + 4);
        if (rest.length > 0) {
          this.onData(rest);
        }
        socket.on('data', (data) => this.onData(data));
        socket.on('close', () => this.flushWaiters(new Error('socket closed')));
        resolve(status);
      };

      socket.on('data', onHandshakeData);
      socket.on('error', reject);
    });
  }

  onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (true) {
      const frame = readFrame(this.buffer);
      if (!frame) {
        return;
      }
      this.buffer = frame.rest;
      if (frame.opcode === 0x9) {
        continue;
      }
      if (frame.opcode === 0x8) {
        this.close();
        return;
      }
      const waiter = this.waiters.shift();
      const event = JSON.parse(frame.text);
      if (waiter) {
        waiter.resolve(event);
      } else {
        this.events.push(event);
      }
    }
  }

  send(value) {
    this.socket.write(frameText(JSON.stringify(value)));
  }

  async waitFor(label, predicate, timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs;
    const skipped = [];

    try {
      while (Date.now() <= deadline) {
        const remaining = Math.max(1, deadline - Date.now());
        const event = await this.next(label, remaining);
        if (predicate(event)) {
          this.events = skipped.concat(this.events);
          return event;
        }
        skipped.push(event);
      }
    } catch (error) {
      this.events = skipped.concat(this.events);
      throw error;
    }

    this.events = skipped.concat(this.events);
    const lastEvent = skipped.length > 0 ? skipped[skipped.length - 1] : null;
    throw new Error(`timed out waiting for ${label}, last event: ${JSON.stringify(lastEvent)}`);
  }

  next(label = 'websocket frame', timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      if (this.events.length > 0) {
        resolve(this.events.shift());
        return;
      }
      const timeout = setTimeout(() => reject(new Error(`timed out waiting for ${label}`)), timeoutMs);
      this.waiters.push({
        resolve: (event) => {
          clearTimeout(timeout);
          resolve(event);
        },
        reject: (error) => {
          clearTimeout(timeout);
          reject(error);
        },
      });
    });
  }

  flushWaiters(error) {
    while (this.waiters.length > 0) {
      this.waiters.shift().reject(error);
    }
  }

  close() {
    if (this.socket) {
      this.socket.end();
      this.socket.destroy();
    }
  }
}

function content(prefix) {
  return `${prefix} ${Date.now()}`;
}

async function main() {
  await new RawWebSocket(wsUrl, 'invalid-token').connect(401);

  const alice = new RawWebSocket(wsUrl, aliceToken);
  const bob = new RawWebSocket(wsUrl, bobToken);
  await alice.connect();
  await bob.connect();

  const liveContent = content('live message');
  alice.send({ type: 'sendMessage', chatId, content: liveContent });
  const live = await bob.waitFor('Bob live newMessage', (event) =>
    event.type === 'newMessage' && event.data.message.content === liveContent);
  bob.send({ type: 'ack', messageId: live.data.message.id });
  await bob.waitFor('Bob live ack', (event) => event.type === 'ack' && event.data.messageId === live.data.message.id);
  bob.close();

  const offlineContents = [content('offline one'), content('offline two'), content('offline three')];
  for (const messageContent of offlineContents) {
    alice.send({ type: 'sendMessage', chatId, content: messageContent });
    await alice.waitFor(`Alice messageSent for ${messageContent}`, (event) =>
      event.type === 'messageSent' && event.data.message.content === messageContent);
  }
  alice.close();

  const replayBob = new RawWebSocket(wsUrl, bobToken);
  await replayBob.connect();
  const replayedMessages = [];
  for (const expectedContent of offlineContents) {
    const replayed = await replayBob.next(`Bob replay newMessage for ${expectedContent}`);
    if (replayed.type !== 'newMessage' || replayed.data.message.content !== expectedContent) {
      throw new Error(`expected replay content '${expectedContent}', got ${JSON.stringify(replayed)}`);
    }
    replayedMessages.push(replayed);
  }
  for (const replayed of replayedMessages) {
    replayBob.send({ type: 'ack', messageId: replayed.data.message.id });
    await replayBob.waitFor(`Bob replay ack for ${replayed.data.message.content}`, (event) =>
      event.type === 'ack' && event.data.messageId === replayed.data.message.id);
  }
  replayBob.close();

  console.log('WebSocket smoke passed.');
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
