# Testing Guide and Terminal Commands

This document outlines the terminal commands required to run, configure, and
test the `auth-service` and Kong API gateway stack.

## 1. Environment Setup

Before starting, ensure your secrets are configured via `.env` at the root of
the project:

```bash
echo "JWT_SECRET=change-me-super-secret-jwt-signing-key-min-32-bytes-0123456789" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
```

## 2. Managing the Stack

We use Docker Compose to manage the microservices. The `auth-service` is scoped
under the `auth` profile.

**Start the stack (Core + Auth Service):**

```bash
docker compose --profile auth up --build -d
```

**Stop and clean up the stack (including volumes):**

```bash
docker compose --profile auth down -v
```

## 3. Configuring the Gateway

Once the containers are running and healthy, configure Kong's routes and
plugins:

```bash
./kong/setup-core.sh
```

## 4. Manual API Testing Using Curl

The API is exposed on `http://localhost:18000`. Here are the commands to
manually test the authentication flow.

### A. Register a New User

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'
```

Expected output: `201 Created` with
`{"message":"user registered","username":"alice"}`.

### B. Handle Validation Errors

```bash
curl -i -s -X POST http://localhost:18000/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":""}'
```

Expected output: `400 Bad Request`.

### C. Log In to Get a JWT Token

```bash
curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}'
```

Expected output: a JSON payload containing the `access_token`.

You can extract the token directly into an environment variable:

```bash
TOKEN=$(curl -s -X POST http://localhost:18000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret123"}' \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
```

### D. Access the Protected `/auth/me` Endpoint

```bash
curl -i -s -X GET http://localhost:18000/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

Expected output: `200 OK` with the user's DB ID and username.

## 5. Automated Smoke Testing

Instead of running manual curl commands, use the built-in smoke test script:

```bash
./auth-service/plug/smoke.sh
```

## 6. Standalone Integration Demo

To prove the service can run independently, use the standalone demo:

```bash
cd examples/auth-standalone
echo "JWT_SECRET=super-secret-jwt-key-for-standalone-demo" > .env
echo "JWT_ISSUER=springboot-auth" >> .env
docker compose up -d
../../auth-service/plug/kong-setup.sh
../../auth-service/plug/smoke.sh
docker compose down -v
```
