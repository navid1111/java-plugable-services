# Comment Service Standalone Integration Demo

This demo proves that `comment-service` can be deployed independently of any
target service. It mounts the auth plug kit for JWTs, then comments on
generic target keys such as `tweeter.post/<id>` and `youtube.video/<id>`
without running tweeter or YouTube services.

## How to run

1. Build the images from the root of the repo:

   ```bash
   docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
   docker build -t comment-service:latest -f comment-service/Dockerfile comment-service/
   ```

2. Create environment variables:

   ```bash
   cd examples/comments-standalone/
   echo "JWT_SECRET=super-secret-jwt-key-for-comments-demo-32-bytes" > .env
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
   ../../comment-service/plug/kong-setup.sh
   ```

5. Run the smoke test:

   ```bash
   ../../comment-service/plug/smoke.sh
   ```

## Tear down

```bash
docker compose down -v
```
