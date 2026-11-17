-- FlywayUpgradeTest only: a migration held back from the "previous release" and applied below the installed head.
-- Versions under 1000 belong to no module block (ADR 0001); it creates and touches only its own scratch objects.
CREATE SCHEMA upgrade_test_held_back;
CREATE TABLE upgrade_test_held_back.first (id integer PRIMARY KEY);
