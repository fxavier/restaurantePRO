# DESIGN.md: Stitch refactor

Status: **APPROVED** 2026-11-20 by Xavier Francisco (UI refactor, Phase 2 gate: dark in both apps, Stitch's exact colours, fixed route colours). Applied in Phase 3. Source: Google Stitch project `15612001253409631373` ("Full App Screen Designs"), its design theme (`designMd`) and the screens listed below. Stitch output is a visual spec only; no Stitch HTML or CSS goes into the codebase.

This document amends [frontend-design-system.md](frontend-design-system.md): its colour tokens and table-state sections are replaced by the dark theme below; its principles, density, touch targets, layouts and component inventory stand unless this document says otherwise.

## Light reference (2026-11-23, replaces Stitch)

Decided by Xavier Francisco on 2026-11-23: the visual reference is now the light example image [reference/pos-tables-light.png](reference/pos-tables-light.png), which **replaces the Stitch dark theme**. Stitch screens stay a *structural* reference only for screens the image does not cover. Rollout: the POS first (this phase, starting with `/tables`); the BackOffice keeps `.dark` until the next phase.

**What the image shows:** a light grey page; white rounded cards; a status bar card (store · terminal · operator; connection and pending chips on the right); a left card with area tabs and table counts (the selected tab solid green), a "Mesa ou nº" search and a grid of tinted table tiles; a right card with the selected table's order (rounds, draft lines with −/qty/+ steppers, a large total, "Subtotal" and "Dividir" outline buttons, a full-width green "Enviar ronda N"); footer chips for alerts and keyboard hints.

**Tokens** (`packages/ui/src/styles/tokens.css`, `:root`; checked by `tokens.test.ts`):

| token | light value | use | contrast (WCAG 2.x) |
| --- | --- | --- | --- |
| `--background` | `#F0F0ED` | page | — |
| `--card`, `--popover` | `#FFFFFF` | cards, dialogs | — |
| `--foreground` | `#1C1C1A` | text | 17.1:1 on card, 15.0:1 on page |
| `--muted` (= secondary, accent) | `#EAEAE6` | hover, raised | — |
| `--muted-foreground` | `#575752` | secondary text | 7.3:1 on card, 6.0:1 on muted |
| `--border` | `#D6D6D1` | 1px borders | decorative |
| `--input` | `#7A7A74` | field borders | 4.3:1 on card (≥3:1) |
| `--primary` (= brand, success) | `#0B7A0B` | the reference green, darkened for AA | 5.5:1 with white; 5.5:1 as text on card |
| `--primary-hover` | `#086208` | pressed / hover | 7.6:1 with white |
| `--primary-foreground` | `#FFFFFF` | text on green | — |
| `--ring` | `#1C64C4` | focus and the selected tile | 5.7:1 on card (≥3:1) |
| `--warning` / `-foreground` | `#9A5700` / `#FFFFFF` | amber fill, amber text | 5.6:1 |
| `--info` / `-foreground` | `#1D5FB0` / `#FFFFFF` | blue fill, blue text | 6.3:1 |
| `--destructive` (= error) / `-foreground` | `#B3261E` / `#FFFFFF` | red fill, error text | 6.5:1 |
| `--success-tint` / `-border` / `-text` | `#D4EDD2` / `#6CC46C` / `#145A14` | free, reserved | text 6.7:1 on tint |
| `--warning-tint` / `-border` / `-text` | `#F9DEA9` / `#E9A21B` / `#6B3E00` | occupied | text 6.9:1 on tint |
| `--info-tint` / `-border` / `-text` | `#D3E3F8` / `#82AFEB` / `#1A4A8A` | bill requested | text 6.7:1 on tint |
| `--destructive-tint` / `-border` / `-text` | `#F8D6D6` / `#E58C8C` / `#8E1B1B` | locked (overdue when the API gives it) | text 6.7:1 on tint |
| `--radius` | `0.5rem` (unchanged) | shared with `.dark` | cards `rounded-2xl` (16 px), tiles and buttons `rounded-xl` (12 px) |

`foreground` and `muted-foreground` pass AA on every tint (≥ 5.3:1), so a tile's code and details need no special colour. The state borders are decorative: every state also has its word or icon and label. The route chips (kitchen, bar, copa) keep their hues as border + 15 % tint with `foreground` text, since white on them is below AA on a light page. `.dark` is untouched; `--radius` and `--route-*` are not set there, so they keep their old values for the BackOffice.

**Table states (light):**

| State (API) | Tile | Text on the tile |
| --- | --- | --- |
| free (`status FREE`) | `bg-success-tint`, `border-success-border` | "Livre" in `success-text`; the seats |
| reserved (`reserved`, `nextReservationAt`) | the state's own tile | "Reservada 20:30" with a clock icon |
| occupied (`status OCCUPIED`) | `bg-warning-tint`, `border-warning-border` | guests · minutes open (from `openedAt`); "Ocupada" for assistive technology |
| bill requested (`subtotalPrinted`) | `bg-info-tint`, `border-info-border` | "Conta pedida" with a receipt icon |
| locked (`locked`) | `bg-destructive-tint`, `border-destructive-border` | "Bloqueada" with a lock icon |
| overdue ("58 min sem ronda") | `destructive` tint | **not built**: the table map has no last-round time |
| selected | the state's tile + a 2px `ring` outside it | `aria-pressed` |

The image's per-table amount is not shown: no current POS query gives it.

**Status bar and footer (2026-11-23, backend extended):**

| Reference | Built as | Source |
| --- | --- | --- |
| "Loja Baixa · POS-02 · Ana M." | store · terminal code (its name when it has none) · employee | `SessionView.terminalCode` / `terminalName` (identity, from `OrganizationApi.findTerminal`) |
| "Ligado" | the existing connection chip (green when the change stream is open) | lib/live |
| "1 impressão pendente" | amber chip in the status bar, when > 0; not a link (the POS has no print queue screen) | `GET /api/v1/pos/production/alerts` `pendingPrintJobs`: jobs waiting (QUEUED, PROCESSING, RETRY_WAIT) on a printer in NORMAL mode, as PRINT_QUEUE_STALLED counts them (a printer on HOLD, Espera §8.6, waits on purpose and is not pending), + FAILED/UNKNOWN of the last 24 h without a retry or acknowledgement on any printer; drawer kicks excluded |
| "1 pagamento por confirmar" | red-tint chip in the `/tables` footer, linking to `/orders/{oldestOrderId}/payment` (unlinked when the only ones are debt payments) | `GET /api/v1/pos/settlement/alerts`: UNKNOWN attempts of the store |
| "Cozinha: 2 rondas > 15 min" | amber chip in the `/tables` footer, linking to `/kds` | `GET /api/v1/pos/production/alerts` `delayedKitchenRounds`: **rounds**, not tickets (a round sent to Cozinha and Bar is one batch with two tickets, and counts once), with a ticket QUEUED or IN_PREPARATION queued longer than `kitchenDelayMinutes` (any station; `restaurantpro.production.kitchen-delay`, default `PT15M`) |
| "F2 reserva · F3 takeaway · / pesquisa artigo" | "F2 reserva · F3 takeaway · / procurar mesa" on `/tables`; F2 only with `RESERVATION_MANAGE`; never while typing in a field or inside a dialog | `routes/tables-shortcuts.tsx` |

Each alert is read only by a session holding the permission that shows it (production: `PRODUCTION_VIEW`; payments: `PAYMENT_COLLECT` or `PAYMENT_RESOLVE`); the backend refuses the others (403). **Two reads, composed in the POS**, not one: no module may depend on both production and settlement (production only listens to settlement's events), and a new module dependency is an architecture decision; each module owns its own count, permission and index. Both follow the change stream (`PRINT_JOBS`, `PRODUCTION_TICKETS`, `PAYMENT_ATTEMPT`); the production read is also polled every 15 s, since a round grows late with time alone.

## Screens used

| Stitch screen | Screen ID | Repo route / files |
|---|---|---|
| POS — Sala e Gestão de Mesas | `490260d04c6544c691d6d99fb5504725` | `/tables`: `apps/pos/src/routes/tables-page.tsx`, `features/dining/components/{table-map-view,table-tile}.tsx`, `routes/status-bar.tsx` |
| POS — Registo de Pedidos e Rondas | `0915c4b97df3450a9254db74db6ebf93` | `/orders/:orderId`: `apps/pos/src/routes/order-page.tsx`, `features/orders/components/*`, `features/catalog/components/*` |
| BackOffice — Lojas, Terminais & Organização | `80048f80f7ab42548ccb6abde024709c` | `/organization/terminals`: `apps/backoffice/src/routes/terminals-page.tsx`, `features/organization/components/*`, `shell/*` |

The other 16 Stitch screens have no route yet; they are the spec for those features when they are built (full table in [CLAUDE.md](../../CLAUDE.md#ui-refactor-google-stitch)).

## Theme decision

Stitch is **dark only**, in both the POS and the BackOffice, and both apps use it. We keep the approved light theme as `:root` (unchanged, still checked by `tokens.test.ts`) and add the Stitch theme as a `.dark` block over the **same token names**, which is how the current design system already planned dark mode. Both apps set `class="dark"` on `<html>`. **Superseded 2026-11-23** by the light reference above: the POS no longer sets `class="dark"`, and `:root` is now the light reference rather than the R1 light theme; the BackOffice keeps `.dark` until its phase.

## Implied design system (from the theme and the three screens)

- **Colour:** tonal layers on charcoal. Base `#0B0F19`, card `#151D2E`, raised/hover `#1E293B`, 1px border `#334155`. Emerald `#10B981` fill with dark text for the primary action; `#4EDEA3` for emerald text. Four table states: free (emerald), occupied (amber), bill requested (sky), locked (red), each as a tinted surface + 2px border + uppercase pill.
- **Type:** Inter only; tabular numerals on every amount and count. Scale: label-sm 12/16 600 uppercase +0.04em · body-sm 14/20 · label-md 14/20 500 · body-md 16/24 · body-lg 18/26 · headline-sm 20/28 600 · label-numeric 20/24 700 · headline-md 24/32 600 · headline-lg 32/40 600 (table numbers) · display-currency 48/56 700 (36/44 on mobile).
- **Spacing:** 4/8/12/16/24 (`space-xs…xl`), gutter 16, page margin 16 → 20 → 24 by breakpoint.
- **Radius:** 4 default, 8 cards, 12 modals and tiles; full for dots and avatars.
- **Elevation:** no blur shadows; tonal layering + crisp 1px borders. Pressed = darker background + coloured border, no transition delay.
- **Breakpoints:** 360–599 single column; 600–1024 two panes (60/40); ≥1025 three columns.
- **Touch:** ≥48px targets, 56px primary actions, 64px keypad keys, ≥8px gap.
- **Recurring components:** top status bar (store, terminal, connection, user, lock); section tabs with a filled emerald active tab; zone/filter pills with counts; summary stat cards with a status dot; table tile; product tile (family chip, code, name, description, price, add icon); order panel (rounds grouped by batch with status chip, draft batch with qty steppers, totals, full-width primary); footer bar (clock, quick lock); BackOffice sidebar with filled active item, page header with eyebrow + title + meta, data table with uppercase label-sm headers, monospace for IDs, MAC and IP.

## Diff against current tokens

| Area | Current (`packages/ui/src/styles`) | Stitch | Proposal |
|---|---|---|---|
| Palette | light, blue `#166FE5` | dark, emerald `#10B981` | **new `.dark` block** (below); `:root` values untouched |
| Font | Inter Variable, tabular-nums | Inter, `tnum` + `cv05` | keep; `cv05` skipped (cosmetic) |
| Type scale | Tailwind defaults (DS steps 12/14/16/18/20/24/28) | table above | **snap**, no new type tokens: label-sm→`text-xs font-semibold uppercase tracking-wide`, body-sm/label-md→`text-sm`, body-md→`text-base`, body-lg→`text-lg`, headline-sm/label-numeric→`text-xl`, headline-md→`text-2xl`, headline-lg 32→`text-3xl` (30), display-currency 48→`text-5xl`, mobile 36→`text-4xl` |
| Spacing | Tailwind 4px scale | 4/8/12/16/24 | **identical** (`1/2/3/4/6`); no tokens |
| Radius | `--radius` 8 → sm 4, md 6, lg 8, xl 12 | sm 2, default 4, md 6, lg 8, xl 12 | **identical** where used; no change |
| Shadow | flat + borders; shadow only on overlays | same (HTML adds `shadow-sm/md`, the theme forbids them) | keep current rule |
| Breakpoints | Tailwind `sm 640`, `lg 1024` | 600, 1025 | **snap** 600→`sm`, 1025→`lg`; no custom breakpoints |
| Touch | 48 / 56 / PIN key 72 | 48 / 56 / keypad 64 | keep (72 ≥ 64) |
| Dark variant | Tailwind v4 default = `prefers-color-scheme` | class-based | `@custom-variant dark (&:is(.dark *));` in `globals.css`, so the shadcn `dark:` classes follow the `.dark` class |

## Token change set

`tokens.css` gains a `.dark` block over the existing names and four new names. `globals.css` gains the `dark` variant and the new colour mappings. Colours are Stitch's exact values.

| token | `.dark` value | Stitch source | contrast (WCAG 2.x) |
|---|---|---|---|
| `--background` | `#0B0F19` | surface-base | — |
| `--foreground` | `#DAE2FD` | on-surface | 14.8:1 on background, 13.1:1 on card |
| `--card`, `--popover` | `#151D2E` | surface-card | — |
| `--muted` (= secondary, accent) | `#1E293B` | surface-card-raised | — |
| `--muted-foreground` | `#BBCABF` | on-surface-variant | 9.9:1 on card, 8.6:1 on muted |
| `--border` | `#334155` | surface-border | decorative |
| `--input` | `#86948A` | outline | 5.3:1 on card (≥3:1, 1.4.11) |
| `--primary`, `--brand` | `#10B981` | primary-container | 6.6:1 as text on card |
| `--primary-foreground` | `#0B0F19` | theme: dark text on emerald | 7.6:1 on primary |
| `--primary-hover`, `--ring` | `#4EDEA3` | primary | 11.2:1 with fg; ≥3:1 on every surface |
| `--success` / `-foreground` | `#10B981` / `#0B0F19` | status-table-free | 7.6:1 |
| `--warning` / `-foreground` | `#F59E0B` / `#0B0F19` | status-table-occupied | 8.9:1 |
| `--destructive` / `-foreground` | `#EF4444` / `#0B0F19` | status-table-locked | 5.1:1 |
| `--info` / `-foreground` **(new name)** | `#0284C7` / `#0B0F19` | status-table-bill | 4.7:1 |
| `--error` **(new name)** | `#FFB4AB` | error | 9.9:1 on card (error text) |
| `--route-kitchen` **(new)** | `#F97316` | route-kitchen | 6.8:1 with `background` text |
| `--route-bar` **(new)** | `#8B5CF6` | route-bar | 4.5:1 with `background` text |
| `--route-copa` **(new)** | `#06B6D4` | route-copa | 7.9:1 with `background` text |

The route tokens live in `:root`, so they are the same in both themes. In the light theme, `--info-foreground` and `--error` alias the existing light values.

**AA with Stitch's exact colours.** Red `#EF4444`, blue `#0284C7` and violet `#8B5CF6` fall below 4.5:1 as small text on the dark surfaces (4.5, 4.1 and 4.0:1). They pass as **fills with dark text** and as borders, so status and route hues are only used that way. Error *text* uses Stitch's own `error` colour (`#FFB4AB`) through `text-error`, which replaced `text-destructive` everywhere (23 call sites, no logic touched). `tokens.test.ts` checks every pair above.

**Table states:** each tile is a tint of the status token (`bg-success/15`, `bg-warning/15`, `bg-info/15`, `bg-destructive/15`) with a 2px border and a filled pill in the status colour. The tint stands in for Stitch's `*-surface` colours. This adds the **bill requested** state, which the API exposes as the `subtotalPrinted` indicator. Status keeps colour plus label or icon (DS principle 3).

**Route chips:** filled `bg-route-*` with `text-background`. The API's production routes are configured per tenant (route create, update and delete), so binding a route to kitchen, bar or copa is a Phase 4 mapping on the order screen; an unmapped route gets a neutral `secondary` badge.

**Not added:**
- The Material 3 tonal set (`surface-container-*`, `*-fixed`, `on-*-container`, `inverse-*`): the screens use a handful of these as hover shades. They snap to `muted` / `card` / `border` / `primary-hover`.
- Material Symbols: we keep lucide-react.

## Components affected (Phase 3)

| Component | Change |
|---|---|
| `Card` | `rounded-xl`, `bg-card`, 1px `border`; no shadow |
| `Button` | semibold; `default` = emerald fill with dark text; `destructive` = red border + red tint + `text-error` (Stitch's cancel and void style); `outline` = raised `muted` in dark; sizes unchanged |
| `Badge` / `StatusBadge` | pill = label-sm uppercase, `rounded-sm`, filled status colour; new `info` variant / tone for the bill state |
| `Tabs` | active trigger filled `primary`, inactive `muted-foreground` |
| `Input` / `Select` | `bg-muted` field, `border-input`, focus ring `ring` |
| `Table` | header row label-sm uppercase `muted-foreground`; row hover `muted` |
| `Dialog` / `AlertDialog` / `Sheet` | `bg-card`, `rounded-xl`, border |
| `globals.css` | `@custom-variant dark` |
| Apps' `index.html` | `<html class="dark">` |

App-only: POS `TableTile`, `StatusBar`, order panel; BackOffice `Sidebar` (filled active item). These belong to Phase 4.

## BackOffice sign-in (Keycloak theme)

The BackOffice signs in through Keycloak's own pages (ADR 0040, step-up per ADR 0035), restyled by the
`restaurantpro` login theme (`deploy/keycloak/themes/restaurantpro`, see its README section): the page background,
the card, the sidebar's brand block, Inter, the emerald primary and pt-PT texts. Its colours repeat the `.dark` values
above as `--rp-*`; a token change here is a change there too.
