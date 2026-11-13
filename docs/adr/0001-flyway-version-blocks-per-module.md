# 0001. Flyway with one version block per module

Status: Accepted, 2026-11-04 (D5; extended by D61 for R2). Source: [backend-decisions.md](../design/backend-decisions.md) D5, [r2-decisions.md](../design/r2-decisions.md) D61.

## Context

Slices are built in parallel worktrees. Each one adds migrations. With one Flyway history, version numbers are where parallel branches collide on merge.

## Decision

Flyway with one history table. Migrations live in `db/migration/<module>/` and are named `V<n>__<module>_<what>.sql`. Each module owns a block of versions: shared/audit `1xxx`, organization `2xxx`, identity `3xxx`, dining `4xxx`, catalog `5xxx`, orders `6xxx`, production `7xxx`, cash `8xxx`, settlement `9xxx`, fiscal `10xxx`. Customers `11xxx`, promotions `12xxx`, inventory `13xxx` and reporting `14xxx` are reserved. A module that changes later uses the next free number in its own block. An applied migration is never edited.

Rejected: timestamps. They still collide in order when branches merge.

## Consequences

- Slices that touch different modules never collide on a version. Flyway compares versions numerically, so `V10001` sorts after `V9999`.
- Out-of-order application is required (amendment 2026-11-13; the original text said it was not needed): a release adds versions to low blocks below the head of an installed database, so `spring.flyway.out-of-order: true` (proven by `FlywayUpgradeTest`).
- As built, identity used `V3005`–`V3009` in R2 (permissions, terminal-session store, `must_change_pin`, login throttle), all inside its block.
- `spring.jpa.hibernate.ddl-auto=validate` checks on every context start that the entities match the migrations.
