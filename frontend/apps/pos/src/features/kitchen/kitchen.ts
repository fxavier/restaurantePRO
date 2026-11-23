import { ApiError, type components } from '@restaurantpro/api-client';
import type { Employee } from '../../lib/auth/pos-session';
import { isVersionConflict } from '../../lib/error-messages';

type Schemas = components['schemas'];
type TicketView = Schemas['com.xavier.backend.production.model.dto.TicketView'];
export type KitchenStatus = NonNullable<NonNullable<TicketView['kitchen']>['status']>;

type TicketItemView = Schemas['com.xavier.backend.production.model.dto.TicketItemView'];

/** A menu component under its item: "1 × Batata a murro". */
export type KitchenComponent = { quantity: string; name: string };

/** A line of a round as the kitchen reads it: "2 × Bife", with its note and its menu components under it. */
export type KitchenItem = { quantity: string; name: string; notes: string[]; components: KitchenComponent[] };

/** A round on its station (ADR 0044), parsed once where the answer comes in. */
export type KitchenTicket = {
  id: string;
  destination: string;
  status: KitchenStatus;
  /** Sent back with every command: a stale one is refused with 409. */
  version: number;
  queuedAt: string;
  /** "Mesa 04 - Pedido #4092", "TAKEAWAY #12": the printed header, the only place the contract names the table. */
  title: string;
  /** "Ronda 2". */
  round: string;
  items: KitchenItem[];
};

/** What the display works on (ADR 0044); BUMPED is history, read only on demand. */
export const ACTIVE = ['QUEUED', 'IN_PREPARATION', 'READY'] as const satisfies readonly KitchenStatus[];

/** The board's columns, oldest round first in each. */
export const COLUMNS = [
  { status: 'QUEUED', title: 'Na fila' },
  { status: 'IN_PREPARATION', title: 'Em preparação' },
  { status: 'READY', title: 'Pronto' },
] as const satisfies readonly { status: KitchenStatus; title: string }[];

export type KitchenCommand = 'start' | 'ready' | 'bump' | 'recall';

/**
 * What a round in each state may do next (ADR 0044: recall steps back once), the first being the card's main action;
 * any other command is refused by the backend, so the card never offers it.
 */
export const NEXT = {
  QUEUED: [{ command: 'start', label: 'Iniciar' }],
  IN_PREPARATION: [{ command: 'ready', label: 'Marcar pronto' }],
  READY: [
    { command: 'bump', label: 'Retirar' },
    { command: 'recall', label: 'Voltar à preparação' },
  ],
  BUMPED: [{ command: 'recall', label: 'Repor' }],
} as const satisfies Record<KitchenStatus, readonly { command: KitchenCommand; label: string }[]>;

type TicketPage = Schemas['com.xavier.backend.shared.web.PageResponseCom.xavier.backend.production.model.dto.TicketView'];

/** One read of the display: the rounds it holds, how many there are in all, and how many of those it does not hold. */
export type KitchenPage = { tickets: KitchenTicket[]; total: number; notShown: number };

/** The server's count is the total (spec §7): a first page shorter than it leaves rounds the screen must own up to. */
export function toKitchenPage(page: TicketPage | undefined): KitchenPage {
  const content = page?.content ?? [];
  const total = Math.max(page?.totalElements ?? 0, content.length);
  return { tickets: toKitchenTickets(content), total, notShown: total - content.length };
}

/**
 * A view without its identity, kitchen progress or station is not a round the display can move: it is dropped. The
 * items are the server's (`items`); a round it could not read them from keeps the ones its printed lines show.
 */
export function toKitchenTickets(views: TicketView[] | undefined): KitchenTicket[] {
  return (views ?? []).flatMap((view) => {
    const { id, destination, kitchen } = view;
    if (!id || !destination || !kitchen?.status || kitchen.version === undefined || !kitchen.queuedAt) return [];
    const printed = readLines(view.lines ?? []);
    const items = view.items?.length ? view.items.map(toKitchenItem) : printed.items;
    return [{ id, destination, status: kitchen.status, version: kitchen.version, queuedAt: kitchen.queuedAt, ...printed, items }];
  });
}

function toKitchenItem({ quantity = '', name = '', notes, components = [] }: TicketItemView): KitchenItem {
  return {
    quantity,
    name,
    notes: notes ? [notes] : [],
    components: components.map((component) => ({ quantity: component.quantity ?? '', name: component.name ?? '' })),
  };
}

const RULE = /^-{3,}$/;
const ITEM = /^(\S+) x (.+)$/;
const NOTE = /^\s+> (.*)$/;
const COMPONENT = /^\s+\+ (.*)$/;

/**
 * A round's printed lines (TicketLayouts.batch): the station, the header, "Ronda n" and the time, a rule, then each
 * item "q x name", its note "   > …" and its components "   + q x …". A line that reads as none of these is kept as an
 * item without a quantity, so nothing printed is ever hidden from the cook.
 */
export function readLines(lines: string[]): Pick<KitchenTicket, 'title' | 'round' | 'items'> {
  const rule = lines.findIndex((line) => RULE.test(line));
  const header = rule < 0 ? lines.slice(0, 1) : lines.slice(0, rule);
  return {
    title: header[1] ?? header[0] ?? '',
    round: header.find((line) => line.startsWith('Ronda ')) ?? '',
    items: readItems(lines.slice(rule < 0 ? 1 : rule + 1)),
  };
}

function readItems(lines: string[]): KitchenItem[] {
  const items: KitchenItem[] = [];
  for (const line of lines) {
    const last = items.at(-1);
    if (last && attachTo(last, line)) continue;
    if (line.trim()) items.push(itemOf(line.trim()));
  }
  return items;
}

/** An indented note or component line goes under the item above it. */
function attachTo(item: KitchenItem, line: string): boolean {
  const note = NOTE.exec(line);
  if (note) item.notes.push(note[1]!);
  const component = COMPONENT.exec(line);
  if (component) item.components.push(counted(component[1]!));
  return !!(note ?? component);
}

function itemOf(line: string): KitchenItem {
  return { ...counted(line), notes: [], components: [] };
}

/** "2 x Bife": its quantity and name; a text without a quantity is all name. */
function counted(text: string): KitchenComponent {
  const item = ITEM.exec(text);
  return item ? { quantity: item[1]!, name: item[2]! } : { quantity: '', name: text };
}

/**
 * The stations offered, each with its count of rounds: the store's (`destinations`, in its order, those without rounds
 * too), then any other a round names, in the order first named; `chosen` stays offered when its last round leaves.
 */
export function stationsOf(tickets: KitchenTicket[], chosen?: string, destinations: string[] = []): { code: string; count: number }[] {
  const counts = new Map<string, number>(destinations.map((code) => [code, 0]));
  for (const { destination } of tickets) counts.set(destination, (counts.get(destination) ?? 0) + 1);
  if (chosen && !counts.has(chosen)) counts.set(chosen, 0);
  return [...counts].map(([code, count]) => ({ code, count }));
}

/** Whole minutes a round has waited since it was queued; a terminal clock behind the server's shows 0, not less. */
export function waitingMinutes(queuedAt: string, now: Date): number {
  return Math.max(0, Math.floor((now.getTime() - Date.parse(queuedAt)) / 60_000));
}

/** Whether the session may watch the kitchen display; for showing it only, the backend enforces. */
export function mayWatchKitchen(employee: Pick<Employee, 'permissions' | 'storeId'> | undefined): boolean {
  return !!employee?.permissions.includes(`PERM_PRODUCTION_VIEW@${employee.storeId}`);
}

/** Whether the session may move rounds (start, ready, bump, recall); for showing commands only. */
export function mayOperateKitchen(employee: Pick<Employee, 'permissions' | 'storeId'> | undefined): boolean {
  return !!employee?.permissions.includes(`PERM_PRODUCTION_OPERATE@${employee.storeId}`);
}

/** Refused because the round moved meanwhile: 409 `ILLEGAL_KITCHEN_STATE`, or a stale version. */
export function isChangedElsewhere(error: unknown): boolean {
  return isVersionConflict(error) || (error instanceof ApiError && error.code === 'ILLEGAL_KITCHEN_STATE');
}
