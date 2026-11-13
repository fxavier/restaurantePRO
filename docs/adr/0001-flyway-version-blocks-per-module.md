# 0001. Flyway with one version block per module

Status: Accepted, 2026-11-04 (D5; extended by D61 for R2); amended 2026-11-13 (out-of-order application and its safety rule). Source: [backend-decisions.md](../design/backend-decisions.md) D5, [r2-decisions.md](../design/r2-decisions.md) D61.

## Context

Slices are built in parallel worktrees. Each one adds migrations. With one Flyway history, version numbers are where parallel branches collide on merge.

## Decision

Flyway with one history table. Migrations live in `db/migration/<module>/` and are named `V<n>__<module>_<what>.sql`. Each module owns a block of versions: shared `1001`–`1099`, audit `1100`–`1999`, organization `2xxx`, identity `3xxx`, dining `4xxx`, catalog `5xxx`, orders `6xxx`, production `7xxx`, cash `8xxx`, settlement `9xxx`, fiscal `10xxx`, customers `11xxx`, promotions `12xxx`, inventory `13xxx`, reporting `14xxx` (all in use since R4). A module that changes later uses the next free number in its own block. An applied migration is never edited.

Rejected: timestamps. They still collide in order when branches merge.

## Consequences

- Slices that touch different modules never collide on a version. Flyway compares versions numerically, so `V10001` sorts after `V9999`.
- Out-of-order application is required (amendment 2026-11-13; the original text said it was not needed): a release adds versions to low blocks below the head of an installed database, so `spring.flyway.out-of-order: true` (proven by `FlywayUpgradeTest`).
- Safety rule for out-of-order (amended 2026-11-13): a migration touches only its own module's schema (no cross-schema DDL, no reference to another module's objects, D6), and a lower block never depends on a higher block. Then the order in which an installation receives the blocks does not matter. A release's migrations are listed in `src/test/resources/db/released-migrations.txt` when it ships. `MigrationNumberingTest` checks that every migration sits in its module's block, names its module, and, if not yet released, is above its block's highest released version. `FlywayUpgradeTest` upgrades a database of the last release to the current set.
- As built, identity used `V3005`–`V3009` in R2 (permissions, terminal-session store, `must_change_pin`, login throttle), all inside its block.
- `spring.jpa.hibernate.ddl-auto=validate` checks on every context start that the entities match the migrations.
