# Turf Service Standalone Integration Demo

This demo proves that `turf-service` can be deployed independently of the
monorepo root stack. It mounts the auth plug kit because all `/bookings`
endpoints are protected by Kong's JWT plugin.

## How to Run

1. Build the images from the root of the repo:

   ```bash
   docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
   docker build -t turf-service:latest -f turf-service/Dockerfile turf-service/
   ```

2. Create environment variables:

   ```bash
   cd examples/turf-standalone/
   echo "JWT_SECRET=super-secret-jwt-key-for-turf-demo-32-bytes" > .env
   echo "JWT_ISSUER=springboot-auth" >> .env
   ```

3. Start the stack:

   ```bash
   docker compose up -d
   ```

4. Register auth, JWT credentials, and turf routes in Kong:

   ```bash
   ../../auth-service/plug/kong-setup.sh
   curl -fsS -X PUT http://localhost:8001/consumers/springboot-auth
   curl -fsS -X POST http://localhost:8001/consumers/springboot-auth/jwt \
     --data "algorithm=HS256" \
     --data "key=springboot-auth" \
     --data "secret=super-secret-jwt-key-for-turf-demo-32-bytes" \
     || echo "jwt credential already exists, skipping."
   ../../turf-service/plug/kong-setup.sh
   ```

5. Run the smoke test:

   ```bash
   ../../turf-service/plug/smoke.sh
   ```

The smoke test covers browse, concurrent booking, conflict, mine, unauthorized
cancel, idempotent cancel, and rebooking the freed slot.

## Tear Down

```bash
docker compose down -v
```
