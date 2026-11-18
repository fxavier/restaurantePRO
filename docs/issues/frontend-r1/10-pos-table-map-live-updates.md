# 10: POS table map and live updates

**Status:** ready-for-agent
**Blocked by:** 09
**Owner:** `frontend-developer`. Spec: PRD stories 31–35, AC6 (partly); §4, §8.4, §9; FC §10; DS table states.

## What to build

The POS home is the store's table map: one tab per area, with fixed-size tiles. A tile shows FREE or OCCUPIED by fill and label, with guests and time open. Locked, subtotal-printed and reserved show as icon-and-label badges, in the DS priority order. A status bar shows the employee, the store, the connection state and "Trocar empregado".

The app subscribes to the POS change stream. A message only invalidates queries: `TABLES` invalidates the map. On reconnect the app invalidates all POS queries. While the stream is down, it polls every 15 s and reconnects with backoff.

## Acceptance criteria

- [ ] App-level tests cover:
  - loading, empty and error states;
  - every state and indicator, each with a label;
  - an SSE `TABLES` message causing a refetch;
  - stream loss leading to polling;
  - reconnect invalidating queries;
  - "Sem ligação".
- [ ] No layout shift when a tile changes state.
- [ ] Axe finds no serious or critical violation.
