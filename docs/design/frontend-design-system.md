# Frontend design system

Status: **APPROVED** 2026-11-18 by Xavier Francisco ("aprovado", frontend R1 Phase 2 gate). Decided with the user in alignment round R4. Conventions: [frontend-conventions.md](../architecture/frontend-conventions.md). The visual direction is approved on screenshots of the first POS and BackOffice screens (Phase 4 gate) before any further screen is built.

## Principles

1. Modern and clean: white surfaces, slate neutrals, one accent (blue), no decoration that carries no information.
2. Few elements per screen; **one primary action per view** (one filled blue button).
3. Status by colour **and** label or icon, never colour alone.
4. Every command gets visible feedback: pressed → in flight (button disabled, spinner) → the server's answer (new state, or an error where the action was).
5. The common path takes at most three taps or clicks; every data view has loading, empty, error and unavailable states.

## Colour tokens

Defined once as CSS variables in `packages/ui/src/styles/tokens.css`, in the variable names shadcn/ui expects, mapped into Tailwind by the installed shadcn version's theme block. Light theme only in R1; dark mode later as a `.dark` block over the same names.

| token | value | use | contrast |
|---|---|---|---|
| `--background` | `#FFFFFF` | page | |
| `--foreground` | `#0F172A` (slate-900) | body text | 17.9:1 on white |
| `--muted` | `#F1F5F9` (slate-100) | subdued surfaces, POS panels | |
| `--muted-foreground` | `#475569` (slate-600) | secondary text | 7.6:1 on white |
| `--border` | `#E2E8F0` (slate-200) | dividers, card edges (decorative) | |
| `--input` | `#64748B` (slate-500) | input and checkbox boundaries | 4.8:1 (≥ 3:1, 1.4.11) |
| `--primary` | `#166FE5` | filled buttons, links, selected | 4.7:1 with white |
| `--primary-hover` | `#1259C3` | hover / pressed fill; link text on `--muted` | 6.5:1 with white, 5.9:1 on muted |
| `--primary-foreground` | `#FFFFFF` | text on primary | |
| `--brand` | `#1877F2` | logo and large accents only, never small text on white | 4.2:1 |
| `--ring` | `#1877F2` | focus ring, 2 px, offset 2 px | ≥ 3:1 on white |
| `--success` / `--success-foreground` | `#15803D` / `#FFFFFF` | confirmed, sent | 5.0:1 |
| `--warning` / `--warning-foreground` | `#FEF3C7` / `#92400E` | attention (reserved, pending) | 6.4:1 |
| `--destructive` / `--destructive-foreground` | `#B91C1C` / `#FFFFFF` | errors, destructive actions, locked | 6.5:1 |
| `--info` | = `--primary` | informational | |

Contrast values are WCAG 2.x ratios, checked again by axe in Playwright. `--primary` text on `--muted` is 4.3:1 and fails AA for small text: links and blue text on muted surfaces use `--primary-hover`.

### POS table states (TableMap)

The API gives `status` (`FREE`, `OCCUPIED`) and independent indicators (`locked`, `subtotalPrinted`, `reserved`) (§8.3, §8.4). The tile shows status by fill and every indicator as an icon + label badge; when several apply, the badges stack in this priority: locked, subtotal printed, reserved.

| state / indicator | tile | label |
|---|---|---|
| FREE | white, `--input` border | "Livre" |
| OCCUPIED | `--primary` fill, white text, guests and time open | "Ocupada" |
| locked | `--destructive` badge, lock icon | "Bloqueada" |
| subtotalPrinted | `--muted` badge, receipt icon | "Subtotal" |
| reserved | `--warning` badge, clock icon, next time | "Reservada 20:30" |

## Typography

Inter variable, self-hosted (`@fontsource-variable/inter`), fallback `system-ui, sans-serif`. Prices and quantities use `font-variant-numeric: tabular-nums`.

| step | BackOffice | POS | weight |
|---|---|---|---|
| caption | 12/16 | 14/20 | 500 |
| body | 14/20 | 16/24 | 400 |
| label / button | 14/20 | 16/24 (primary 18/24) | 500–600 |
| title | 18/28 | 20/28 | 600 |
| page heading | 24/32 | 24/32 | 600 |
| total (order panel) | — | 28/36 | 700 |

## Spacing, radius, elevation

- Spacing: Tailwind's 4 px scale. Page gutter 24 px BackOffice, 16 px POS; card padding 16 px.
- Radius: `--radius` 8 px (controls, cards); 12 px for POS tiles; full for badges.
- Elevation: flat surfaces with borders; shadow only on overlays (dialog, popover, toast).

## Density and touch targets

| | POS (comfortable) | BackOffice (compact) |
|---|---|---|
| minimum target | 48 × 48 px | 36 px control height, ≥ 24 px target (WCAG 2.5.8) |
| primary action | 56 px high, full width of its pane | 36 px |
| gap between targets | ≥ 8 px | ≥ 4 px |
| product tile | ≥ 112 × 80 px | — |
| PIN pad key | 72 × 72 px | — |
| table tile | ≥ 120 × 96 px | — |

POS: no hover-only affordance, no long-press or swipe for a command, no layout shift when a state changes (fixed-size tiles, reserved space for badges).

## Layouts

- **POS order screen** (1024 × 768 minimum, landscape): family rail (left, 160 px) | product grid with search (centre, fills) | order panel (right, 360 px): lines grouped as "Por enviar" (draft) and "Enviado" (confirmed, per round), the server's total, and one primary "Pedir" button.
- **POS table map**: area tabs on top, a responsive grid of table tiles, a status bar with the employee, the store, the connection state and "Trocar empregado".
- **BackOffice**: left sidebar (240 px, collapses to 64 px icons below 1280 px) with the §5 areas the user may see; top bar with tenant switcher (only with more than one membership), store selector (`?storeId`), user menu; content max width 1280 px.

## Component inventory (R1)

Base shadcn components installed in `packages/ui`: Button, Input, Label, Form (React Hook Form binding), Card, Badge, Dialog, AlertDialog, Sheet, Tabs, Table, Select, DropdownMenu, Separator, Skeleton, Tooltip (BackOffice only), Sonner (toast), Alert.

Shared primitives built on them, only when both apps need them:

| primitive | purpose |
|---|---|
| `ConnectionBanner` | "Sem ligação — nenhuma operação é confirmada", shown while unavailable |
| `StatusBadge` | colour + icon + label from a status map |
| `Money` | `Intl.NumberFormat` with the API's currency, tabular numerals |
| `DataState` | loading (Skeleton), empty, error (with retry), unavailable for any query |
| `ConfirmButton` | primary button that disables and shows progress while its mutation is in flight |

App-only components (POS: `PinPad`, `NumericKeypad`, `TableTile`, `ProductTile`, `OrderPanel`, `FamilyRail`; BackOffice: `AppShell`, `TenantSwitcher`, `StoreSelect`, `StepUpDialog`) stay in their app until the other app needs them.

Icons: lucide-react, 20 px BackOffice, 24 px POS, always with a text label or `aria-label`.
