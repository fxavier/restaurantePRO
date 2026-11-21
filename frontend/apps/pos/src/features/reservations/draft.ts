import type { components } from '@restaurantpro/api-client';
import type { Reservation } from './reservation';
import { storeClock, storeInstant } from './store-time';

type ReservationRequest = components['schemas']['com.xavier.backend.dining.model.dto.ReservationRequest'];

/** The new or edited reservation as the host types it: text, as the inputs hold it, until it is sent. */
export type Draft = {
  /** `YYYY-MM-DD`, the store's day. */
  date: string;
  /** `HH:MM` on the store's clock. */
  time: string;
  partySize: string;
  tableIds: string[];
  contactName: string;
  contactPhone: string;
  notes: string;
  /** The customer record it is linked to, if any. */
  customerId: string | null;
  /** Not on the form: the reservation's own, carried through so an edit (a full replacement) keeps it. */
  durationMinutes: number | null;
};

export function emptyDraft(date: string): Draft {
  return {
    date,
    time: '',
    partySize: '',
    tableIds: [],
    contactName: '',
    contactPhone: '',
    notes: '',
    customerId: null,
    durationMinutes: null,
  };
}

/** The reservation being edited, as the draft it would have been. */
export function draftOf(reservation: Reservation, timeZone: string): Draft {
  const { date, time } = storeClock(reservation.startsAt, timeZone);
  return {
    date,
    time,
    partySize: String(reservation.partySize),
    tableIds: reservation.tableIds,
    contactName: reservation.contactName,
    contactPhone: reservation.phone ?? '',
    notes: reservation.notes ?? '',
    customerId: reservation.customerId,
    durationMinutes: reservation.durationMinutes,
  };
}

/** What the contract requires: a day and a time, a party of at least one, a name. The rest is the server's to judge. */
export function isComplete(draft: Draft): boolean {
  return draft.date !== '' && draft.time !== '' && Number(draft.partySize) >= 1 && draft.contactName.trim() !== '';
}

/** The request body (create; an update adds the version): empty optional texts are left out. */
export function toRequest(draft: Draft, timeZone: string): ReservationRequest {
  const phone = draft.contactPhone.trim();
  const notes = draft.notes.trim();
  return {
    startsAt: storeInstant(draft.date, draft.time, timeZone),
    partySize: Number(draft.partySize),
    contactName: draft.contactName.trim(),
    ...(phone && { contactPhone: phone }),
    ...(notes && { notes }),
    ...(draft.customerId && { customerId: draft.customerId }),
    tableIds: draft.tableIds,
    ...(draft.durationMinutes !== null && { durationMinutes: draft.durationMinutes }),
  };
}
