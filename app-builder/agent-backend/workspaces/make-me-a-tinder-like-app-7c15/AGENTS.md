# App-builder workspace instructions

Build only the static frontend files in this directory. `index.html` is the entry point.

## Backend ground truth

- Gateway base URL: `http://localhost:18000`
- Read `.hermes/skills/plugs/SKILL.md` before wiring backend calls.
- Only call endpoints listed below or in that skill. Never invent services or paths.
- Never call `/internal/*` from browser code; those routes require service workload identity.
- Logout only removes the local JWT. `DELETE /auth/me` permanently deactivates the account.
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
  - `DELETE /auth/me`
  - `GET /auth/me`
  - `POST /auth/login`
  - `POST /auth/register`
  - `PUT /auth/profile`
- Bff (`bff`):
  - `GET /bff/feed`
  - `GET /bff/posts/{id}`
- Booking Service (`booking-service`):
  - `DELETE /bookings/{id}`
  - `GET /bookings/mine`
  - `GET /bookings/resources`
  - `POST /bookings`
- Comment Service (`comment-service`):
  - `DELETE /comments/{id}`
  - `GET /comments/targets/{targetType}/{targetId}`
  - `GET /comments/targets/{targetType}/{targetId}/summary`
  - `GET /comments/{id}`
  - `POST /comments/targets/{targetType}/{targetId}`
- Leetcode Service (`leetcode-service`):
  - `GET /leetcode/admin/problems/{id}`
  - `GET /leetcode/competitions/{id}/leaderboard`
  - `GET /leetcode/problems`
  - `GET /leetcode/problems/{id}`
  - `GET /leetcode/submissions/{id}`
  - `POST /leetcode/admin/problems`
  - `POST /leetcode/competitions`
  - `POST /leetcode/problems/{id}/submit`
  - `PUT /leetcode/admin/problems/{id}`
- Media Service (`media-service`):
  - `DELETE /media/{id}`
  - `GET /media/targets/{targetType}/{targetId}`
  - `GET /media/targets/{targetType}/{targetId}/summary`
  - `GET /media/{id}`
  - `POST /media/targets/{targetType}/{targetId}`
  - `POST /media/upload-intents`
  - `POST /media/upload-intents/{id}/fail`
  - `POST /media/upload-intents/{id}/finalize`
- Post Search Service (`post-search-service`):
  - `GET /post-search`
  - `GET /post-search/documents/{targetType}/{targetId}`
- Tweeter Service (`tweeter-service`):
  - `DELETE /posts/users/{userId}/follow`
  - `DELETE /posts/{id}`
  - `GET /posts`
  - `GET /posts/feed`
  - `GET /posts/{id}`
  - `POST /posts`
  - `PUT /posts/users/{userId}/follow`
  - `PUT /posts/{id}`
- Whatsapp Service (`whatsapp-service`):
  - `GET /chat/chats`
  - `GET /chat/chats/{id}/messages`
  - `POST /chat/chats`

## Required feedback loop

After writing or changing backend fetch code, run `python3 verify-frontend-contracts.py .`.
Do not edit, delete, weaken, or bypass `verify-frontend-contracts.py`.
The coding-agent sandbox intentionally cannot open network sockets. Do not run
`./verify-backend.sh` there and do not report sandbox curl errors as backend failures.
App Builder's server runs the canonical linter plus CORS and official live service smokes
outside the sandbox before releasing the preview. `verify-backend.sh` remains available
for a human to run manually from a normal host shell.
