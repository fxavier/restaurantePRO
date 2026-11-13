# 0038. ESC/POS driver: printer profiles, styled tickets, status probe and drawer kick

Status: Accepted 2026-11-12 (R5 approval, D154–D158; P59, P60); built in S59 and S60, integrated in S63 (2026-11-13); D158 amended 2026-11-13 (S60 and S63). Source: [r5-decisions.md](../design/r5-decisions.md) §6; spec §4, §8.6, §12, §15. Amends D57's single fixed-width rendering. Hardware validation is the human checkpoint A67 ([B5 checklist](../qa/test-plans/printer-hardware-b5.md)).

## Context

D57 renders plain text lines with a per-printer code page, a QR code, a feed and a partial cut over TCP 9100. Printer models are still open (§15). Kitchen and receipt printers differ in width (58/80 mm), cutter and drawer port; §4 needs the cash drawer for cash payments; §12 requires tests with the real printers (launch condition B5). The fake printer of the tests must keep proving behaviour at byte level.

## Decision

- Target the **ESC/POS common subset of Epson TM-compatible printers** with a **profile per printer**: paper width and columns, code page and an optional table-number override, cut mode, feed lines, an optional status probe, drawer pin and pulse. Presets fill the form; stored fields are authoritative. Defaults reproduce R4 bytes.
- Production's own tickets are stored **styled** (`STYLED_V1` JSON lines: bold, double, tall, alignment, rules, left/right amounts) and laid out at send time for the printer that prints them, with word wrap at its width. R4 tickets and fiscal printouts stay `PLAIN` and are only wrapped.
- **Single-byte code pages only** (default CP850 offered for new printers); no UTF-8.
- **Status probe before sending** (`DLE EOT 2` and `4`, 500 ms each): cover open, paper end or error sends nothing and retries like an unreachable printer; no answer sends. **No post-send acknowledgement**: `DLE EOT` is answered before the buffered job prints, and the alternatives are vendor-specific; §4 already accepts that physical exactly-once is not guaranteed.
- **Cash-drawer kick** as a print job of its own (`ESC p`), requested by settlement through the event `CashDrawerKickRequested` for cash payments and refunds, plus a manual audited command; one attempt, never held, redirected or retried, expired 30 s after the payment or refund was recorded (D158 amended).

## Alternatives considered

- **UTF-8 output:** one encoding everywhere, but only recent Epson models accept it, and clones print garbage.
- **Kick bytes prefixed to the receipt:** no new job type, but the document is issued at close, often after the cash payment.
- **Vendor drivers (Epson ePOS, Star):** acknowledgements and richer status, but a dependency per brand while the models are unknown.

## Consequences

- Columns on `production.printer`, `production_ticket.content_format` (V7008), ticket source `DRAWER_KICK` (V7009).
- `TcpFakePrinter` answers status queries; golden byte tests per profile.
- B5 stays a human checkpoint: the checklist `docs/qa/test-plans/printer-hardware-b5.md` (S63) is run on each purchased model.
