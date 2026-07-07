import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const port = Number(process.env.PORT || 18080);
const kongHttp = new URL(process.env.KONG_URL || 'http://localhost:18000');
const kongWs = new URL(process.env.WS_KONG_URL || 'ws://localhost:18000');
const root = path.dirname(fileURLToPath(import.meta.url));

function readRequestBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });
}

function sendJson(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body));
}

async function serveIndex(response) {
  const html = await fs.readFile(path.join(root, 'index.html'));
  response.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  response.end(html);
}

async function proxyHttp(request, response) {
  const target = new URL(request.url, kongHttp);
  const headers = { ...request.headers };
  delete headers.host;
  delete headers.connection;

  const body = request.method === 'GET' || request.method === 'HEAD'
    ? undefined
    : await readRequestBody(request);

  const upstream = await fetch(target, {
    method: request.method,
    headers,
    body,
  });

  const responseHeaders = {};
  upstream.headers.forEach((value, key) => {
    responseHeaders[key] = value;
  });
  response.writeHead(upstream.status, responseHeaders);
  if (upstream.body) {
    for await (const chunk of upstream.body) {
      response.write(chunk);
    }
  }
  response.end();
}

function websocketAccept(key) {
  return crypto
    .createHash('sha1')
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest('base64');
}

function proxyWebSocket(request, socket, head) {
  const requestUrl = new URL(request.url, `http://${request.headers.host}`);
  const tokenFromQuery = requestUrl.searchParams.get('token');
  const authHeader = request.headers.authorization || (tokenFromQuery ? `Bearer ${tokenFromQuery}` : '');
  if (!authHeader) {
    socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    return;
  }

  const upstreamSocket = net.createConnection({
    host: kongWs.hostname,
    port: Number(kongWs.port || 80),
  });
  const upstreamKey = crypto.randomBytes(16).toString('base64');
  const upstreamPath = requestUrl.pathname;

  upstreamSocket.on('connect', () => {
    const lines = [
      `GET ${upstreamPath} HTTP/1.1`,
      `Host: ${kongWs.host}`,
      'Upgrade: websocket',
      'Connection: Upgrade',
      'Sec-WebSocket-Version: 13',
      `Sec-WebSocket-Key: ${upstreamKey}`,
      `Authorization: ${authHeader}`,
      '',
      '',
    ];
    upstreamSocket.write(lines.join('\r\n'));
  });

  let handshake = Buffer.alloc(0);
  const onHandshakeData = chunk => {
    handshake = Buffer.concat([handshake, chunk]);
    const end = handshake.indexOf('\r\n\r\n');
    if (end === -1) {
      return;
    }

    upstreamSocket.off('data', onHandshakeData);
    const header = handshake.subarray(0, end).toString('utf8');
    const statusLine = header.split('\r\n')[0];
    const status = Number(statusLine.split(' ')[1]);
    if (status !== 101) {
      socket.end(`HTTP/1.1 ${status || 502} Upstream WebSocket Failed\r\nConnection: close\r\n\r\n`);
      upstreamSocket.destroy();
      return;
    }

    socket.write([
      'HTTP/1.1 101 Switching Protocols',
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Accept: ${websocketAccept(request.headers['sec-websocket-key'])}`,
      '',
      '',
    ].join('\r\n'));

    const rest = handshake.subarray(end + 4);
    if (head.length > 0) {
      upstreamSocket.write(head);
    }
    if (rest.length > 0) {
      socket.write(rest);
    }
    socket.pipe(upstreamSocket);
    upstreamSocket.pipe(socket);
  };

  upstreamSocket.on('data', onHandshakeData);
  upstreamSocket.on('error', () => {
    socket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n');
  });
  socket.on('error', () => upstreamSocket.destroy());
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host}`);
    if (url.pathname === '/' || url.pathname === '/index.html') {
      await serveIndex(response);
      return;
    }
    if (url.pathname.startsWith('/auth') || url.pathname.startsWith('/posts') || url.pathname.startsWith('/chat')) {
      await proxyHttp(request, response);
      return;
    }
    sendJson(response, 404, { error: 'not found' });
  } catch (error) {
    sendJson(response, 502, { error: error.message });
  }
});

server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url, `http://${request.headers.host}`);
  if (url.pathname === '/chat/ws') {
    proxyWebSocket(request, socket, head);
    return;
  }
  socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
});

server.listen(port, () => {
  console.log(`Facebook composition page: http://localhost:${port}`);
  console.log(`Proxying backend calls through ${kongHttp.href}`);
});
