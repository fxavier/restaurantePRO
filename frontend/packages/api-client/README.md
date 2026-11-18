# api-client

`openapi.json` is a committed snapshot of the backend's OpenAPI document (`/v3/api-docs`), the source of the typed
client (ADR 0042, frontend conventions §7). Do not edit it by hand.

Refresh it from `backend/` (needs Docker for the test database; no running server):

```bash
rtk mvn test -Dtest=OpenApiSnapshotTest -Dopenapi.snapshot=write
```

Then commit the diff together with the backend change that caused it. Without `-Dopenapi.snapshot=write`, the same test
runs in the backend suite and fails when this file differs from what the code produces, naming the first differing line.

The file has sorted keys and the layout of `JSON.stringify(value, null, 2)`, so diffs are readable and formatters leave
it unchanged. Schema names are fully qualified Java class names (D10, `springdoc.use-fqn`).
