import { formatMoney } from '@restaurantpro/ui';
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { baixa } from '../../test/fixtures';
import { mzn, orderRow, ordersServer } from '../../test/orders-fixtures';
import { renderApp } from '../../test/render-app';

const ORDERS = `/orders?storeId=${baixa.id}`;

function cells(table: HTMLElement) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent));
}

describe('orders search', () => {
  it("lists the store's orders, newest first as the server sends them, with times in the store's timezone", async () => {
    const state = ordersServer({
      rows: [
        orderRow(1043),
        orderRow(1042, {
          mode: 'TAKEAWAY',
          tableCode: null,
          guests: null,
          lifecycle: 'CLOSED',
          openedAt: '2026-11-20T17:00:00Z',
          closedAt: '2026-11-20T17:25:00Z',
          due: mzn('0.00'),
        }),
      ],
    });

    renderApp(ORDERS);

    const table = await screen.findByRole('table', { name: 'Pedidos' });
    // Maputo is UTC+2: 18:05Z opens at 20:05 on the store's clock.
    await waitFor(() =>
      expect(cells(table)).toEqual([
        ['#1043', 'Mesa', 'M12', '4', 'Aberto', '20/11/2026', '20:05', '—', formatMoney(mzn('1250.50'))],
        ['#1042', 'Take-away', '—', '—', 'Fechado', '20/11/2026', '19:00', '19:25', formatMoney(mzn('0.00'))],
      ]),
    );
    expect(screen.queryByText(/€/)).not.toBeInTheDocument();
    expect(Object.fromEntries(state.queries[0]!)).toEqual({ storeId: baixa.id, page: '0', size: '50' });
  });

  it('searches by period, state, mode and number or table, and refuses a period that ends before it starts', async () => {
    const state = ordersServer({ rows: [orderRow(1043)] });
    const { user } = renderApp(ORDERS);
    await screen.findByRole('table', { name: 'Pedidos' });

    await user.type(screen.getByLabelText('De'), '2026-11-21');
    await user.type(screen.getByLabelText('Até'), '2026-11-20');
    expect(await screen.findByText('A data inicial é posterior à final.')).toBeInTheDocument();
    const asked = state.queries.length;
    await user.clear(screen.getByLabelText('Até'));
    await user.type(screen.getByLabelText('Até'), '2026-11-22');
    await user.selectOptions(screen.getByLabelText('Estado'), 'Fechado');
    await user.selectOptions(screen.getByLabelText('Modo'), 'Take-away');
    await user.type(screen.getByLabelText('Número ou mesa'), ' m12 ');
    await user.click(screen.getByRole('button', { name: 'Pesquisar' }));

    await waitFor(() =>
      expect(Object.fromEntries(state.queries.at(-1)!)).toEqual({
        storeId: baixa.id,
        from: '2026-11-21',
        to: '2026-11-22',
        status: 'CLOSED',
        mode: 'TAKEAWAY',
        q: 'm12',
        page: '0',
        size: '50',
      }),
    );
    expect(state.queries.slice(asked).every((query) => query.get('to') !== '2026-11-20')).toBe(true);
    expect(screen.queryByText('A data inicial é posterior à final.')).not.toBeInTheDocument();
  });

  it('shows the count the server gives and pages through it, back to the first page on a new search', async () => {
    const state = ordersServer({ rows: [orderRow(1)], totalElements: 73 });
    const { user } = renderApp(ORDERS);

    expect(await screen.findByText('73 pedidos')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));
    expect(await screen.findByText('Página 2 de 2')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Estado'), 'Aberto');

    await waitFor(() => expect(state.queries.at(-1)!.get('status')).toBe('OPEN'));
    expect(state.queries.map((query) => query.get('page'))).toEqual(['0', '1', '0']);
  });
});
