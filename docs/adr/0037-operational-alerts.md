# 0037. Operational alerts: one AlertSource contract, metrics for Prometheus, an in-app list, a backup status file

Status: Proposed 2026-11-12 (D149–D153; P58). Source: [r5-decisions.md](../design/r5-decisions.md) §5; spec §11, §12. Amends, once accepted, D33 (scrape token for `/actuator/prometheus`). S63 sets it to Accepted after approval.

## Context

§12: "Actuator e métricas com acesso restrito. Alertas para backup em atraso, espaço em disco, fila de impressão, eventos falhados, pagamentos desconhecidos, divergência de caixa e falha de exportação. Não usar order_id em labels de métricas." Several metrics exist (event publications, in-doubt payments, print jobs, cash closing difference, stock drift). Backups run outside the JVM in `deploy/` scripts, and the backend container cannot see the backup directory (D98). Not every installation will run Prometheus.

## Decision

- **`shared.ops.AlertSource`** (`List<OperationalAlert> alerts()`) implemented next to the data in each module; `config` aggregates them into `GET /api/v1/backoffice/alerts` (`SYSTEM_MONITOR`, filtered by store scope, cached 30 s) and a health component with the custom status `ALERT`, which never turns the container unhealthy.
- **Metrics** for every alert, labelled only by store, status, state, module, kind, outcome or name; never an order, payment, job or document id.
- **Backup age and disk space** reach the application through **`deploy/status/backup-status.json`**, written atomically by `verify-backups.sh` every 15 minutes and mounted read-only; a stale file is itself an alert.
- **Scrape authentication:** `/actuator/prometheus` also accepts a bearer token from a secret file, valid for that endpoint only.
- **`deploy/monitoring/alert-rules.yml`** with the same thresholds, and an opt-in Compose profile `monitoring` running a pinned Prometheus on the internal network. No Alertmanager is shipped.

## Alternatives considered

- **Actuator health groups as the alert list:** standard, but a group naming a missing contributor fails startup, health has no counts or stores, and a DOWN component would restart the container.
- **Heartbeat table written by the backup scripts:** no mount, but a host script would write a migration-owned table, and a restore would bring back old heartbeats.
- **node_exporter textfile collector:** standard for Prometheus, useless for installations without it.

## Consequences

- New secret `metrics_token` and a read-only mount `./status`; `verify-backups.sh` gains the status file.
- Proofs: `AlertsEndpointTest`, `AlertHealthNeverDownTest`, `MetricsScrapeTokenTest`, `MetricLabelsTest`, `deploy/test-monitoring.sh --self-test`, and `AlertRulesReferenceExistingMetricsTest` in S63.
