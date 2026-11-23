import { describe, expect, it } from 'vitest';
import { readLines, stationsOf, toKitchenPage, toKitchenTickets, waitingMinutes } from './kitchen';

describe('readLines', () => {
  it('reads a takeaway header, a decimal quantity, and keeps a line it cannot read as an item', () => {
    expect(readLines(['BAR', 'TAKEAWAY #12', 'Ronda 1', '22/11/2026 12:00', '-----', '0.5 x Vinho da casa', 'Sem gelo'])).toEqual({
      title: 'TAKEAWAY #12',
      round: 'Ronda 1',
      items: [
        { quantity: '0.5', name: 'Vinho da casa', notes: [], components: [] },
        { quantity: '', name: 'Sem gelo', notes: [], components: [] },
      ],
    });
  });

  it('without a rule, takes the first line as the title and the rest as items', () => {
    expect(readLines(['Mesa 2', '1 x Sopa'])).toEqual({
      title: 'Mesa 2',
      round: '',
      items: [{ quantity: '1', name: 'Sopa', notes: [], components: [] }],
    });
  });

  it('nothing printed is nothing to read', () => {
    expect(readLines([])).toEqual({ title: '', round: '', items: [] });
  });
});

describe('toKitchenTickets', () => {
  it('drops a ticket without kitchen progress (not a round)', () => {
    expect(toKitchenTickets([{ id: 'a', destination: 'KITCHEN', lines: [] }])).toEqual([]);
  });
});

describe('toKitchenPage', () => {
  const round = { id: 'a', destination: 'KITCHEN', lines: [], kitchen: { status: 'QUEUED', version: 0, queuedAt: '2026-11-22T09:00:00Z' } } as const;

  it("takes the server's total, and counts the rounds beyond the page it holds", () => {
    const read = toKitchenPage({ content: [round], page: 0, size: 200, totalElements: 201 });
    expect(read.total).toBe(201);
    expect(read.notShown).toBe(200);
    expect(read.tickets).toHaveLength(1);
  });

  it('a page holding every round leaves none out; no answer is an empty page', () => {
    expect(toKitchenPage({ content: [round], page: 0, size: 200, totalElements: 1 }).notShown).toBe(0);
    expect(toKitchenPage(undefined)).toEqual({ tickets: [], total: 0, notShown: 0 });
  });
});

describe('stationsOf', () => {
  const at = (destination: string) => ({ destination }) as Parameters<typeof stationsOf>[0][number];

  it('counts each station in the order first seen, and keeps the chosen one when it has no round', () => {
    expect(stationsOf([at('KITCHEN'), at('BAR'), at('KITCHEN')], 'COPA')).toEqual([
      { code: 'KITCHEN', count: 2 },
      { code: 'BAR', count: 1 },
      { code: 'COPA', count: 0 },
    ]);
  });
});

describe('waitingMinutes', () => {
  it('counts whole minutes since the round was queued, never fewer than zero', () => {
    const now = new Date('2026-11-22T10:00:30Z');
    expect(waitingMinutes('2026-11-22T09:36:00Z', now)).toBe(24);
    expect(waitingMinutes('2026-11-22T10:01:00Z', now)).toBe(0);
  });
});
