import { describe, expect, it } from 'vitest';
import { BOOKED, CUSTOMER_ID, DAY } from '../../test/reservations';
import { id } from '../../test/table-map';
import { draftOf, emptyDraft, isComplete, toRequest, type Draft } from './draft';
import { toReservations } from './reservation';

const MAPUTO = 'Africa/Maputo';

const filled: Draft = {
  ...emptyDraft('2026-11-20'),
  time: '20:30',
  partySize: '4',
  contactName: '  Rui Sousa ',
  contactPhone: ' 84 555 1234 ',
  notes: ' Aniversário ',
  tableIds: [id(1)],
};

describe('a reservation draft', () => {
  it('starts on the day shown, with nothing else chosen', () => {
    expect(emptyDraft('2026-11-21')).toEqual({
      date: '2026-11-21',
      time: '',
      partySize: '',
      tableIds: [],
      contactName: '',
      contactPhone: '',
      notes: '',
      customerId: null,
      durationMinutes: null,
    });
  });

  it('is complete with a day, a time, a party of at least one and a name', () => {
    expect(isComplete(filled)).toBe(true);
    expect(isComplete({ ...filled, date: '' })).toBe(false);
    expect(isComplete({ ...filled, time: '' })).toBe(false);
    expect(isComplete({ ...filled, partySize: '' })).toBe(false);
    expect(isComplete({ ...filled, partySize: '0' })).toBe(false);
    expect(isComplete({ ...filled, partySize: '1' })).toBe(true);
    expect(isComplete({ ...filled, contactName: '   ' })).toBe(false);
  });

  it("becomes the request: the store's time as a UTC instant, texts trimmed, the empty ones left out", () => {
    expect(toRequest(filled, MAPUTO)).toEqual({
      startsAt: '2026-11-20T18:30:00.000Z',
      partySize: 4,
      contactName: 'Rui Sousa',
      contactPhone: '84 555 1234',
      notes: 'Aniversário',
      tableIds: [id(1)],
    });
    expect(toRequest({ ...filled, contactPhone: ' ', notes: '', customerId: CUSTOMER_ID }, MAPUTO)).toEqual({
      startsAt: '2026-11-20T18:30:00.000Z',
      partySize: 4,
      contactName: 'Rui Sousa',
      customerId: CUSTOMER_ID,
      tableIds: [id(1)],
    });
    // A full replacement (PUT) keeps the duration the reservation was booked with.
    expect(toRequest({ ...filled, durationMinutes: 90 }, MAPUTO)).toMatchObject({ durationMinutes: 90 });
  });

  it('a reservation being edited starts as the draft it would have been', () => {
    const booked = toReservations([BOOKED])[0]!;
    expect(draftOf(booked, MAPUTO)).toEqual({
      date: '2026-11-20',
      time: '19:30',
      partySize: '4',
      tableIds: [id(1), id(10)],
      contactName: 'Ana Ferreira',
      contactPhone: '841234567',
      notes: 'Cadeira de bebé',
      customerId: CUSTOMER_ID,
      durationMinutes: 120,
    });
    const bare = toReservations([DAY[3]!])[0]!;
    expect(draftOf(bare, MAPUTO)).toMatchObject({ contactPhone: '', notes: '', customerId: null, tableIds: [] });
  });
});
