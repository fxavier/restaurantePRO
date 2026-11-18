# 11: POS order: open a table and draft lines

**Status:** ready-for-agent
**Blocked by:** 10
**Owner:** `frontend-developer`. Spec: PRD stories 36–44; §4, §8.3, §8.4; FC §8, §12; DS order screen.

## What to build

**Opening a table.** Tapping a free table asks for the number of guests on a numeric pad and opens it. Tapping an occupied table goes to its order. A locked table, or one just taken by someone else, is refused with the reason.

**The order screen.** It has three panes:
- a family rail;
- a product grid with search and code entry, including the barcode scanner as keyboard input;
- an order panel.

**Adding items.** Adding an item asks for its variant and its menu choices (respecting min and max) when it has them, plus an optional note. Each tap is one intent with its own `Idempotency-Key`. Draft lines can change quantity or be removed.

**The order panel** shows "Por enviar" (draft) and "Enviado" (per round), and the server's prices and total, formatted with `pt-MZ` and the API's currency.

## Acceptance criteria

- [ ] App-level tests cover:
  - opening a free table;
  - opening an occupied table;
  - a locked table;
  - a table just taken (409);
  - adding with a variant, with menu choices and with a note;
  - changing and removing a draft line;
  - totals taken from the server only;
  - search and code entry;
  - "Sem ligação" disabling commands;
  - a retry reusing the same key.
- [ ] An `ORDER` SSE message refetches the open order.
- [ ] Targets are 48 px, and the layout works at 1024×768.
