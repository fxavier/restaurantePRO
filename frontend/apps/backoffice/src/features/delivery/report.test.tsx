import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { delivery, joao, reportServer, tiago } from '../../test/delivery-fixtures';
import { baixa } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';

const REPORT = `/delivery/report?storeId=${baixa.id}`;
/** 22:30 UTC is 00:30 of the next day in Maputo (UTC+2): the default range is the store's today, not UTC's. */
const NOW = new Date('2026-11-20T22:30:00Z');
const TODAY = '2026-11-21';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

function cells(table: HTMLElement) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent));
}

describe('delivery report', () => {
  it("lists the store's deliveries of its today, with courier, state and times in the store's timezone", async () => {
    const state = reportServer({
      deliveries: [
        delivery(1082, 'DELIVERED', {
          courierEmployeeId: joao.id,
          dispatchedAt: '2026-11-21T18:17:00Z',
          deliveredAt: '2026-11-21T18:29:00Z',
        }),
        delivery(1083, 'OUT_FOR_DELIVERY', { courierEmployeeId: tiago.id, dispatchedAt: '2026-11-21T18:40:00Z' }),
        delivery(1084, 'PENDING', { promisedAt: undefined }),
      ],
    });

    renderApp(REPORT);

    const table = await screen.findByRole('table', { name: 'Entregas' });
    expect(await within(table).findByText(joao.name!)).toBeInTheDocument();
    expect(cells(table)).toEqual([
      ['#1082', 'Z1', 'João Pereira', 'Entregue', '20:30', '20:17', '20:29'],
      ['#1083', 'Z1', 'Tiago Ramos', 'Em rota', '20:30', '20:40', '—'],
      ['#1084', 'Z1', '—', 'Pendente', '—', '—', '—'],
    ]);
    expect(Object.fromEntries(state.queries[0]!)).toEqual({
      storeId: baixa.id,
      from: TODAY,
      to: TODAY,
      page: '0',
      size: '50',
    });
  });
});
