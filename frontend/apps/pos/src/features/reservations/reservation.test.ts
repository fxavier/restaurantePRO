import { describe, expect, it } from 'vitest';
import { DAY } from '../../test/reservations';
import { MAP } from '../../test/table-map';
import { matches, summarize, tableCodes, toReservations, type Reservation } from './reservation';

const day = toReservations(DAY);
const byName = (name: string) => day.find((reservation) => reservation.contactName === name)!;

describe('reservations', () => {
  it('maps the contract, with what the screen may lack as null or empty', () => {
    expect(byName('Ana Ferreira')).toEqual({
      id: DAY[2]!.id,
      version: 3,
      status: 'BOOKED',
      startsAt: '2026-11-20T17:30:00Z',
      partySize: 4,
      contactName: 'Ana Ferreira',
      phone: '841234567',
      customerId: DAY[2]!.customerId,
      notes: 'Cadeira de bebé',
      tableIds: DAY[2]!.tableIds,
      cancelReason: null,
    });
    expect(byName('Carla Lopes')).toMatchObject({ phone: null, customerId: null, notes: null, tableIds: [] });
    expect(byName('Carla Lopes').cancelReason).toBe('O cliente telefonou');
  });

  it.each(['id', 'version', 'status', 'startsAt', 'partySize', 'contactName'] as const)(
    'an answer without %s is incomplete',
    (field) => {
      const { [field]: _, ...rest } = DAY[2]!;
      expect(() => toReservations([rest])).toThrow('Incomplete reservation');
    },
  );

  it('no answer at all is incomplete; an empty day is not', () => {
    expect(() => toReservations(undefined)).toThrow('Incomplete reservations');
    expect(toReservations([])).toEqual([]);
  });

  it('names tables by their codes on the map, skipping one the map no longer has', () => {
    const areas = MAP.areas.map((area) => ({ ...area, tables: area.tables.map((t) => ({ ...t })) }));
    expect(tableCodes([MAP.areas[1]!.tables[0]!.id, MAP.areas[0]!.tables[0]!.id], areas)).toBe('E1 + M1');
    expect(tableCodes(['gone', MAP.areas[0]!.tables[1]!.id], areas)).toBe('M2');
    expect(tableCodes([], areas)).toBe('');
  });

  it('counts the day by state, with the people of each', () => {
    expect(summarize(day)).toEqual({
      ALL: { count: 4, people: 14 },
      BOOKED: { count: 1, people: 4 },
      SEATED: { count: 1, people: 3 },
      NO_SHOW: { count: 1, people: 2 },
      CANCELLED: { count: 1, people: 5 },
    });
    expect(summarize([])).toEqual({
      ALL: { count: 0, people: 0 },
      BOOKED: { count: 0, people: 0 },
      SEATED: { count: 0, people: 0 },
      NO_SHOW: { count: 0, people: 0 },
      CANCELLED: { count: 0, people: 0 },
    });
  });

  describe('search and state filter', () => {
    const ana: Reservation = byName('Ana Ferreira');

    it('any state matches "ALL"; otherwise only its own', () => {
      expect(matches(ana, { status: 'ALL', query: '' })).toBe(true);
      expect(matches(ana, { status: 'BOOKED', query: '' })).toBe(true);
      expect(matches(ana, { status: 'SEATED', query: '' })).toBe(false);
    });

    it('finds a name ignoring case and accents, or a phone by its digits', () => {
      expect(matches(ana, { status: 'ALL', query: 'ferr' })).toBe(true);
      expect(matches(ana, { status: 'ALL', query: '  ANA  ' })).toBe(true);
      expect(matches(byName('Família Costa'), { status: 'ALL', query: 'familia' })).toBe(true);
      expect(matches(ana, { status: 'ALL', query: '84 123' })).toBe(true);
      expect(matches(ana, { status: 'ALL', query: 'costa' })).toBe(false);
      expect(matches(ana, { status: 'ALL', query: '999' })).toBe(false);
      expect(matches(byName('Carla Lopes'), { status: 'ALL', query: '84' })).toBe(false);
    });

    it('the search and the state must both match', () => {
      expect(matches(ana, { status: 'SEATED', query: 'ana' })).toBe(false);
    });
  });
});
