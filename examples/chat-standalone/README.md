# Chat Service Standalone Integration Demo

This demo proves that the `whatsapp-service` can be deployed independently of
the rest of the monorepo, using only its Docker image and plug kit. It also
mounts the auth plug kit because chat endpoints are protected by Kong's JWT
plugin.

## How to run

1. Build the images from the root of the repo:

   ```bash
   docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
   docker build -t whatsapp-service:latest -f whatsapp-service/Dockerfile whatsapp-service/
   ```

2. Create environment variables:

   ```bash
   cd examples/chat-standalone/
   echo "JWT_SECRET=super-secret-jwt-key-for-chat-demo-32-bytes" > .env
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
     || echo "jwt credential already exists, skipping."
   ../../whatsapp-service/plug/kong-setup.sh
   ```

5. Run the smoke test:

   ```bash
   ../../whatsapp-service/plug/smoke.sh
   ```

## Tear down

```bash
docker compose down -v
```
