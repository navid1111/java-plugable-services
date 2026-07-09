# App-builder workspace instructions

Build only the static frontend files in this directory. `index.html` is the entry point.

## Backend ground truth

- Gateway base URL: `http://localhost:18000`
- Read `.hermes/skills/plugs/SKILL.md` before wiring backend calls.
- Only call endpoints listed below or in that skill. Never invent services or paths.
- Browser requests are cross-origin; Kong must answer CORS preflight. If CORS fails, show a real error.
- Content services require JWT auth. Do not call `/posts`, `/media`, `/comments`, or `/post-search` anonymously.

## Required auth flow

```js
const GATEWAY = "http://localhost:18000";
async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!(options.body instanceof FormData)) headers['Content-Type'] = headers['Content-Type'] || 'application/json';
  const token = localStorage.getItem('appbuilder.jwt');
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(GATEWAY + path, { ...options, headers });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}
async function login(username, password) {
  const res = await fetch(GATEWAY + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  const data = await res.json();
  localStorage.setItem('appbuilder.jwt', data.access_token);
}
```

## Real endpoint inventory from `/api/plugs/endpoints`

- Auth Service (`auth-service`):
  - `GET /auth/me`
  - `POST /auth/login`
  - `POST /auth/register`
- Comment Service (`comment-service`):
  - `DELETE /comments/{id}`
  - `GET /comments/targets/{targetType}/{targetId}`
  - `GET /comments/{id}`
  - `POST /comments/targets/{targetType}/{targetId}`
- Media Service (`media-service`):
  - `DELETE /media/{id}`
  - `GET /media/targets/{targetType}/{targetId}`
  - `GET /media/{id}`
  - `POST /media/targets/{targetType}/{targetId}`
- Post Search Service (`post-search-service`):
  - `GET /post-search`
  - `GET /post-search/documents/{targetType}/{targetId}`
  - `PUT /post-search/documents/{targetType}/{targetId}`
  - `PUT /post-search/documents/{targetType}/{targetId}/like-count`
- Turf Service (`turf-service`):
  - `DELETE /bookings/{id}`
  - `GET /bookings/mine`
  - `GET /bookings/venues`
  - `POST /bookings`
- Tweeter Service (`tweeter-service`):
  - `DELETE /posts/users/{username}/follow`
  - `GET /posts`
  - `GET /posts/feed`
  - `GET /posts/{id}`
  - `POST /posts`
  - `PUT /posts/users/{username}/follow`
- Whatsapp Service (`whatsapp-service`):
  - `GET /chat/chats`
  - `GET /chat/chats/{id}/messages`
  - `POST /chat/chats`

## Required feedback loop

After writing or changing backend fetch code, run `./verify-backend.sh` from this directory.
Do not claim the backend is wired until it passes. If it fails, fix the frontend contract or report the exact blocker.
