# Auth Service Standalone Integration Demo

This demo proves that the `auth-service` can be deployed independently of the rest of the monorepo, using only its Docker image and the `plug` kit.

## How to run

1. **Build the image (from the root of the repo)**:
   Since this demo uses the pre-built image, you must build it first:
   ```bash
   cd ../../
   docker build -t auth-service:latest -f auth-service/Dockerfile auth-service/
   cd examples/auth-standalone/
   ```

2. **Create the environment variables**:
   ```bash
   echo "JWT_SECRET=super-secret-jwt-key-for-standalone-demo" > .env
   echo "JWT_ISSUER=auth-standalone" >> .env
   ```

3. **Start the stack**:
   ```bash
   docker compose up -d
   ```

4. **Register the routes with Kong**:
   Once Kong is healthy (`docker compose ps` shows `kong` as healthy), run the plug kit setup script:
   ```bash
   ../../auth-service/plug/kong-setup.sh
   ```

5. **Run the smoke test**:
   ```bash
   ../../auth-service/plug/smoke.sh
   ```

## Tear down

```bash
docker compose down -v
```
