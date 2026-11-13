# Printer hardware checklist (B5, acceptance A67)

Owner: QA, run by a person at the printer. Sources: spec §4, §8.6, §12, §15; [r5-decisions.md](../../design/r5-decisions.md) D154–D158 and A67; [ADR 0038](../../adr/0038-escpos-printer-profiles.md); [deploy/README.md](../../../deploy/README.md) "Printers".

**Status: not run.** The automated tests prove the bytes restaurantPRO sends (`EscPosRendererGoldenTest`, `CodePageBytesTest`, `StatusProbeTest`, `DrawerKickBytesTest`, `R5AcceptanceTest`). Only a person can see what a real printer does with them, so A67 stays open until this checklist has been run on **every printer model the restaurant buys** and its results table is filled in and committed. No slice can close A67.

## Before you start

- One installation reachable from the BackOffice, with a store, a terminal, a register and a published catalog that has at least one kitchen item whose name is longer than one printed line (e.g. "Prego no pao com queijo da serra, ovo estrelado e molho") and one item with accents ("Açorda de marisco à moda").
- The printer on the store LAN with a fixed IP address, ESC/POS over TCP port 9100, 58 mm or 80 mm paper, a full roll and a spare one. For the drawer steps: a cash drawer cabled to the printer's DK port.
- Note the printer's model, firmware (from its self-test page: hold FEED while powering on), interface and the drawer's model.
- In the BackOffice, `GET /api/v1/backoffice/printer-profiles` lists the presets: start from `EPSON_TM_80` for an Epson TM-T20/T88 class 80 mm printer, `GENERIC_80_42` for an 80 mm clone, `GENERIC_58` for a 58 mm printer. Create the printer with the preset's fields, the right code page (CP860 for Portuguese on most Epson models) and, if it has a drawer, `drawerPin` (0 = connector pin 2, 1 = pin 5) and `drawerPulseMs` (100 to start).
- Route a kitchen destination to it, or assign it as the terminal's receipt printer, depending on what the steps below need.

Record each step as **PASS**, **FAIL** (with what you saw, and a photo of the paper) or **N/A** (with why). A FAIL that a profile change fixes (code table, columns, cut mode) is a PASS with the profile recorded, not a defect.

## Steps, per model

| # | step | how | expected |
|---|---|---|---|
| 1 | Test page: code page | `POST /api/v1/backoffice/printers/{id}/test` | The line `Acentos: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç àç ãõ` prints every accent correctly. Wrong characters: set `codeTable` to the vendor's table number for the same code page (the printer's self-test page lists them) and print again. |
| 2 | Test page: width ruler | same page | The ruler of `columns` digits fills one line exactly, without wrapping and without a short line. Wrapped: lower `columns`; short: raise it (58 mm: 24–35; 80 mm: 36–64). |
| 3 | Test page: styles | same page | The BOLD line is bold, the DOUBLE line is double width and height, the TALL line is double height only, each on one line. |
| 4 | A kitchen round | confirm a round with the long item and the accented item on a POS | The destination title is large and centred, the table/order header bold, item lines tall; the long name wraps at a space with the continuation indented under the item text (after "1 x "), never cut mid-word unless a single word is longer than the line; the copy mark (reprint) is bold and centred. |
| 5 | A 58 mm receipt (58 mm models) | close an order on a terminal whose receipt printer is this one | The document and the receipt slip fit 32 columns (or the configured width): totals right-aligned, no line broken in the middle of an amount. |
| 6 | Cut mode PARTIAL | set `cutMode` PARTIAL, print the test page | The paper is cut leaving a hinge after `feedLines` blank lines; the last printed line is above the cutter, not cut through. |
| 7 | Cut mode FULL | set `cutMode` FULL, print | A full cut (on printers with only a partial cutter: record which happens). |
| 8 | Cut mode NONE | set `cutMode` NONE, print | No cut; the paper feeds `feedLines` lines so the ticket can be torn off by hand. |
| 9 | Paper out, probe ON | `statusProbe` true; take the roll out; confirm a round | Nothing prints; the job shows RETRY_WAIT with `PAPER_OUT` on the POS and in the BackOffice, then FAILED after 5 attempts; `GET /api/v1/backoffice/alerts` shows `PRINT_QUEUE_STALLED` or `PRINT_JOBS_ATTENTION` for the store. Reload paper and retry the job: it prints once. |
| 10 | Paper out, probe OFF | `statusProbe` false; same | Record what the printer does: the job is SENT (the bytes went into the printer's buffer) and the ticket may be lost; the remedy is a reprint (§4). Also record whether the model answers `DLE EOT` at all (step 9 behaves like this step when it does not). |
| 11 | Paper near end | probe ON; a roll near its end (or the near-end sensor covered) | The job prints, and the printer list shows `lastStatus = PAPER_NEAR_END`. N/A if the model has no near-end sensor. |
| 12 | Cover open | probe ON; open the cover; confirm a round | Nothing prints; RETRY_WAIT with `COVER_OPEN`; closing the cover and waiting for the next attempt prints it once. |
| 13 | Power off mid-job | start a long ticket (a reprint of a big round) and switch the printer off while it prints | The job ends UNKNOWN (sent in part, outcome not known), never resent by itself; a person acknowledges it or retries it from the print queue. Record what was physically printed. |
| 14 | Drawer kick pulse | cash payment on the terminal | The drawer opens once, promptly (within a second or two of the payment). If it does not open, or chatters: try `drawerPulseMs` 50, 100, 200 and the other `drawerPin`, record the working values. A card payment opens nothing. |
| 15 | Manual drawer open | `POST /api/v1/pos/drawer-kicks {reason}` from the POS | The drawer opens once; the audit shows `DRAWER_OPENED_MANUALLY` with the reason. |
| 16 | Drawer with the printer not ready | (a) the printer in mode HOLD, cash payment; (b) mode NORMAL, probe ON, cover open, cash payment | (a) The kick job is FAILED `PRINTER_NOT_NORMAL` at once and the drawer never opens later on its own when the mode goes back to NORMAL: open it with the key or the manual command. (b) A kick is sent without a status probe (D158), so the drawer opens although the cover is open: record whether this model accepts `ESC p` with the cover open. |
| 17 | Network unplugged | pull the printer's network cable; confirm a round; plug it back within a minute | RETRY_WAIT `NOT_CONNECTED`, then the ticket prints once when the cable is back (before the fifth attempt); with the cable out longer, FAILED and visible, and a retry prints it once. |

## Results

Fill in one column per model (add columns as needed). Commit the filled-in table with the date, the person who ran it and the backend commit.

| # | step | model 1: ________ | model 2: ________ |
|---|---|---|---|
| | firmware / interface / paper | | |
| | profile used (width / columns / code page / code table / cut / feed / probe / drawer pin / pulse) | | |
| 1 | test page: code page | | |
| 2 | test page: width ruler | | |
| 3 | test page: styles | | |
| 4 | kitchen round | | |
| 5 | 58 mm receipt | | |
| 6 | cut PARTIAL | | |
| 7 | cut FULL | | |
| 8 | cut NONE | | |
| 9 | paper out, probe on | | |
| 10 | paper out, probe off | | |
| 11 | paper near end | | |
| 12 | cover open | | |
| 13 | power off mid-job | | |
| 14 | drawer kick pulse | | |
| 15 | manual drawer open | | |
| 16 | drawer, printer not ready | | |
| 17 | network unplugged | | |
| | run by / date / backend commit | | |

A FAIL that no profile setting fixes is a bug: file it under `docs/qa/bugs/` with the model, the step, the photo and the job's id and `last_error`, and link it here and in the acceptance matrix (A67).
