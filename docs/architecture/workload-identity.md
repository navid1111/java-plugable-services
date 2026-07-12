# Workload identity

Internal HTTP calls use short-lived HS256 workload JWTs, not a static shared header. Each
calling service has its own signing secret and immutable issuer name. Receiving services map
trusted issuer names to keys and reject missing, forged, expired, excessive-lifetime,
wrong-audience, and missing-scope tokens.

## Claims and policy

| Claim | Rule |
|---|---|
| `iss`, `sub` | both equal the immutable calling service name |
| `aud` | exact receiving service name |
| `scope` | space-separated least-privilege operation scopes |
| `iat`, `exp` | tokens are issued for 60 seconds; receivers reject TTL above 2 minutes |
| `jti` | unique token ID for audit correlation |

Current scopes are `identity:export`, `posts:export`, `search:inspect`, and `search:rebuild`.
Backfill and reconciliation clients mint a new token for every request/page.

## Key management and production deployment

Development defaults exist only so local Compose starts without a secret manager. Production
must provide a distinct `WORKLOAD_JWT_SECRET` to each caller and an explicit
`WORKLOAD_TRUSTED_CALLERS` map to each receiver through the deployment secret store. Do not
reuse the end-user JWT key.

Rotate one caller at a time: add its new key to every receiver, deploy the caller with the new
key, wait longer than the two-minute acceptance window, then remove the old key. A future mTLS
service mesh can replace signing-key distribution while retaining the same audience/scope
authorization model.
