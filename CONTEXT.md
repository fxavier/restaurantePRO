# restaurantPRO — domain glossary

Canonical terms from spec §8.1. Code uses the English name; the Portuguese term is what users say. One concept, one name: do not add synonyms ("site", "restaurant", "location" all mean Store).

| Code name | PT term | Module | Meaning |
|---|---|---|---|
| CompanyProfile | Empresa | organization | The single legal entity of this installation. Exactly one row. Created at bootstrap. |
| Store | Loja | organization | Operating unit of the company. Has timezone, business-day cutoff, currency. `storeId` scopes operations and permissions. |
| Terminal | Terminal | organization | A registered POS station. Enrolled once with a one-time code; then identified by a device token. |
| BusinessDay | Dia comercial | organization (rule), cash (entity, R2) | A trading day that may cross midnight; starts at the store's cutoff time. |
| UserAccount | Conta | identity | Login credential (BackOffice password). |
| Employee | Empregado | identity | Operational person; logs in on POS with code + PIN. |
| EmployeeStoreAssignment | Atribuição a loja | identity | Employee × Store × Role. Many per employee. |
| Role | Função | identity | Named, editable set of Permissions. |
| Permission | Permissão | identity | Code-defined capability (enum), checked server-side. |
| Approver | Aprovador | identity | Second employee whose credentials authorize a critical action; recorded in audit. |
| RoomArea | Área / sala | dining | Dining room, terrace, counter. |
| RestaurantTable | Mesa | dining | Physical table in a RoomArea. |
| TableOccupation | Ocupação | dining (R2) | One use of a table, linked to an Order. At most one open per table. |
| Family / Subfamily | Família / Subfamília | catalog | Grouping of items for POS navigation. |
| Item / ItemVariant | Artigo / Variante | catalog | Commercial product and its variants. Stock items belong to inventory, not catalog. |
| Unit | Unidade | catalog | Sale unit (piece, kg…). |
| TaxCategory / TaxRate | Categoria / Taxa de imposto | catalog | Configurable tax data; no country seeded. |
| PriceList / ItemPrice | Lista de preço / Preço | catalog | Price per item(-variant) per list. |
| ProductionDestination | Destino de produção | catalog (reference), production (R2) | Kitchen/bar/pantry zone an item is routed to. |
| ConfigurationRelease | Publicação | catalog | Frozen catalog snapshot: DRAFT → VALIDATED → PUBLISHED → RETIRED. |
| AuditEvent | Evento de auditoria | audit | Append-only record: actor, approver, action, entity, store, terminal, instant, reason. |
| Money | Valor monetário | shared | BigDecimal + Currency, HALF_UP at line level, largest-remainder allocation. |
| IdempotencyRecord | Chave de idempotência | shared | Stored (scope, key, request hash, response) for repeatable commands. |
