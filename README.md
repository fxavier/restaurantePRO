# restaurantePRO

## Run locally (development)

Requires JDK 25 and Docker. From `backend/`:

```bash
./run-local.sh          # starts the dev PostgreSQL (backend/compose.yaml) and the backend on :8080
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html. Locally it loads before login. Log in with `POST /api/v1/auth/login` (`admin` / `ChangeMe-123`), then change the password with `POST /api/v1/auth/password`, which is required before anything else. Swagger sends the CSRF token, so "Try it out" works for writes.
- Health: http://localhost:8081/actuator/health
- If the dev database was created with another password: `docker compose down -v` (deletes local dev data only), then run `./run-local.sh` again.
- Tests: `./mvnw clean test`. They start their own PostgreSQL through Testcontainers, so the dev database is not needed.

This setup is for development only. Production runs from `deploy/`, where Swagger needs a session; see `deploy/README.md`.
