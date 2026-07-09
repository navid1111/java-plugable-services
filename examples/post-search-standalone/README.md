# Post Search Standalone Integration Demo

This demo composes four independent plug kits behind a fresh Kong:
`auth-service`, `tweeter-service`, `comment-service`, and
`post-search-service`. It proves the host app can create posts, attach
comments to post references, index post snapshots, and search them without
adding service-to-service dependencies.

## How to run

1. Build the images from the root of the repo:

   ```bash
   docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
   docker build -t tweeter-service:latest -f tweeter-service/Dockerfile tweeter-service/
   docker build -t comment-service:latest -f comment-service/Dockerfile comment-service/
   docker build -t post-search-service:latest -f post-search-service/Dockerfile post-search-service/
   ```

2. Create environment variables:

   ```bash
   cd examples/post-search-standalone/
   echo "JWT_SECRET=super-secret-jwt-key-for-post-search-demo-32-bytes" > .env
   echo "JWT_ISSUER=springboot-auth" >> .env
   ```

3. Start the stack:

   ```bash
   docker compose up -d
   ```

4. Register the routes and JWT credential with Kong:

   ```bash
   ../../auth-service/plug/kong-setup.sh
   JWT_SECRET="$(grep '^JWT_SECRET=' .env | cut -d= -f2-)"
   JWT_ISSUER="$(grep '^JWT_ISSUER=' .env | cut -d= -f2-)"
   curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth
   curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
     --data "algorithm=HS256" \
     --data "key=${JWT_ISSUER}" \
     --data "secret=${JWT_SECRET}" \
     || curl -fsS -X PATCH "http://localhost:8001/consumers/springboot-auth/jwt/${JWT_ISSUER}" \
       --data "algorithm=HS256" \
       --data "key=${JWT_ISSUER}" \
       --data "secret=${JWT_SECRET}"
   ../../tweeter-service/plug/kong-setup.sh
   ../../comment-service/plug/kong-setup.sh
   ../../post-search-service/plug/kong-setup.sh
   ```

5. Run the full integration smoke test:

   ```bash
   ./smoke.sh
   ```

## Tear down

```bash
docker compose down -v
```
