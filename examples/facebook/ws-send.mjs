import crypto from 'node:crypto';
import net from 'node:net';

const [wsUrl, token, chatIdRaw, content] = process.argv.slice(2);

if (!wsUrl || !token || !chatIdRaw || !content) {
  console.error('usage: node ws-send.mjs ws://host/chat/ws <token> <chat-id> <content>');
  process.exit(2);
}

const chatId = Number(chatIdRaw);

function frameText(payload) {
  const body = Buffer.from(payload);
  const header = [0x81];
  if (body.length < 126) {
    header.push(0x80 | body.length);
  } else {
    header.push(0x80 | 126, (body.length >> 8) & 0xff, body.length & 0xff);
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
  }
  if (length === 127) {
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

  connect() {
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
      const onHandshakeData = chunk => {
        handshake = Buffer.concat([handshake, chunk]);
        const end = handshake.indexOf('\r\n\r\n');
        if (end === -1) {
          return;
        }
        socket.off('data', onHandshakeData);
        const head = handshake.subarray(0, end).toString('utf8');
        const status = Number(head.split('\r\n')[0].split(' ')[1]);
        if (status !== 101) {
          reject(new Error(`expected websocket status 101, got ${status}`));
          socket.destroy();
          return;
        }
        this.socket = socket;
        const rest = handshake.subarray(end + 4);
        if (rest.length > 0) {
          this.onData(rest);
        }
        socket.on('data', data => this.onData(data));
        socket.on('close', () => this.flushWaiters(new Error('socket closed')));
        resolve();
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
      const event = JSON.parse(frame.text);
      const waiter = this.waiters.shift();
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

  waitFor(label, predicate, timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      const started = Date.now();
      const loop = () => {
        this.next(label, timeoutMs).then(event => {
          if (predicate(event)) {
            resolve(event);
            return;
          }
          if (Date.now() - started > timeoutMs) {
            reject(new Error(`timed out waiting for ${label}, last event: ${JSON.stringify(event)}`));
            return;
          }
          loop();
        }, reject);
      };
      loop();
    });
  }

  next(label, timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      if (this.events.length > 0) {
        resolve(this.events.shift());
        return;
      }
      const timeout = setTimeout(() => reject(new Error(`timed out waiting for ${label}`)), timeoutMs);
      this.waiters.push({
        resolve: event => {
          clearTimeout(timeout);
          resolve(event);
        },
        reject: error => {
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

const ws = new RawWebSocket(wsUrl, token);
await ws.connect();
ws.send({ type: 'sendMessage', chatId, content });
await ws.waitFor('messageSent', event =>
  event.type === 'messageSent' && event.data.message.content === content);
ws.close();
console.log('WebSocket send passed.');
