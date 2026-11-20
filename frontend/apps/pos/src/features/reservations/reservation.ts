import type { components } from '@restaurantpro/api-client';

export type ReservationView = components['schemas']['com.xavier.backend.dining.model.dto.ReservationView'];

/** §8.3: BOOKED → SEATED, CANCELLED or NO_SHOW; only a BOOKED one changes. */
export type ReservationStatus = NonNullable<ReservationView['status']>;

/** A reservation as the screen shows it; informative only (D126): no capacity or overlap check. */
export type Reservation = {
  id: string;
  /** Sent back with every change (D17). */
  version: number;
  status: ReservationStatus;
  /** ISO-8601 UTC. */
  startsAt: string;
  partySize: number;
  contactName: string;
  /** Digits only, as the backend keeps it. */
  phone: string | null;
  customerId: string | null;
  notes: string | null;
  tableIds: string[];
  cancelReason: string | null;
};

/** A table of the store's map, as this feature needs it: the page hands the map in (a feature never reads another's). */
export type ReservableTable = { id: string; code: string; seats: number; status: 'FREE' | 'OCCUPIED'; locked: boolean };
export type ReservableArea = { id: string; name: string; tables: ReservableTable[] };

/** "M1 + E1": the codes of the tables the map knows, in the reservation's order. */
export function tableCodes(tableIds: string[], areas: ReservableArea[]): string {
  const codes = new Map(areas.flatMap((area) => area.tables.map((table) => [table.id, table.code] as const)));
  return tableIds.flatMap((tableId) => codes.get(tableId) ?? []).join(' + ');
}

/** A state, or every state. */
export type StatusFilter = ReservationStatus | 'ALL';

const STATUS_FILTERS: StatusFilter[] = ['ALL', 'BOOKED', 'SEATED', 'NO_SHOW', 'CANCELLED'];

export type DaySummary = Record<StatusFilter, { count: number; people: number }>;

/** How many reservations, and how many people, the day has in each state (counts only: no capacity check, D126). */
export function summarize(reservations: Reservation[]): DaySummary {
  const summary = Object.fromEntries(STATUS_FILTERS.map((status) => [status, { count: 0, people: 0 }])) as DaySummary;
  for (const { status, partySize } of reservations) {
    for (const key of ['ALL', status] as const) {
      summary[key].count += 1;
      summary[key].people += partySize;
    }
  }
  return summary;
}

const fold = (text: string) => text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();

/** The state filter, and the search: part of the name (case and accents ignored), or of the phone's digits. */
export function matches(reservation: Reservation, { status, query }: { status: StatusFilter; query: string }): boolean {
  if (status !== 'ALL' && reservation.status !== status) return false;
  const name = fold(query.trim());
  const digits = query.replace(/\D/g, '');
  return (
    fold(reservation.contactName).includes(name) || (digits !== '' && (reservation.phone ?? '').includes(digits))
  );
}

/** The contract marks no field required; an answer missing what the screen needs is an error, not a crash. */
export function toReservations(views: ReservationView[] | undefined): Reservation[] {
  if (!views) throw new Error('Incomplete reservations');
  return views.map(toReservation);
}

function toReservation(view: ReservationView): Reservation {
  const { id, version, status, startsAt, partySize, contactName } = view;
  if (!id || version === undefined || !status || !startsAt || partySize === undefined || contactName === undefined) {
    throw new Error('Incomplete reservation');
  }
  return {
    id,
    version,
    status,
    startsAt,
    partySize,
    contactName,
    phone: view.contactPhoneDigits ?? null,
    customerId: view.customerId ?? null,
    notes: view.notes ?? null,
    tableIds: view.tableIds ?? [],
    cancelReason: view.cancelReason ?? null,
  };
}
