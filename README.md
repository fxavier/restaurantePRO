# restaurantePRO

## Run locally (development)

Requires JDK 25 and Docker. From `backend/`:

```bash
./run-local.sh          # starts the dev PostgreSQL (backend/compose.yaml) and the backend on :8080
```

- Sign-in (R6): Keycloak, started by `run-local.sh` at http://localhost:8180 with the realms of `deploy/keycloak` (invitation emails land in Mailpit, http://localhost:8025). The backend only accepts Keycloak access tokens (`Authorization: Bearer`); it keeps no session and has no CSRF. The BackOffice SPA signs in through the BFF (`bff/`, see `bff/README.md`), which holds the tokens server-side and relays its calls.
- Swagger UI: http://localhost:8080/swagger-ui/index.html. Locally it loads without a token; "Try it out" needs a Keycloak access token pasted into "Authorize".
- Mock data: `./dev-seed.sh` (store DEV with its catalog, customers, inventory and today's sales) is not usable in R6 yet: its program still signs in with the R5 local login, which R6 removed. See the header of `backend/dev-seed.sh`.
- Health: http://localhost:8081/actuator/health
- If the dev database was created with another password: `docker compose down -v` (deletes local dev data only), then run `./run-local.sh` again.
- Tests: `./mvnw clean test`. They start their own PostgreSQL through Testcontainers, so the dev database is not needed.

This setup is for development only. Production runs from `deploy/`, where Swagger needs a token too; see `deploy/README.md`.
