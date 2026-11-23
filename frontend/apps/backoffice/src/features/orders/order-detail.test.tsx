import { formatMoney } from '@restaurantpro/ui';
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { baixa, sommerschield } from '../../test/fixtures';
import { mzn, orderRow, ordersServer, orderView } from '../../test/orders-fixtures';
import { renderApp } from '../../test/render-app';

const ORDERS = `/orders?storeId=${baixa.id}`;

function cells(table: HTMLElement) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent));
}

describe('order detail', () => {
  it('opens the chosen order with its lines, rounds, discounts and what is due, as the server holds them', async () => {
    const order = orderView(1043);
    const state = ordersServer({ rows: [orderRow(1043)], detail: order });
    const { user, currentUrl } = renderApp(ORDERS);

    await user.click(await screen.findByRole('button', { name: 'Ver pedido #1043' }));

    const detail = await screen.findByRole('region', { name: 'Pedido #1043' });
    expect(currentUrl()).toContain(`orderId=${order.id}`);
    expect(state.details).toEqual([order.id]);
    await waitFor(() => expect(within(detail).getByText(/^20\/11\/2026,? 20:05$/)).toBeInTheDocument());
    expect(within(detail).getByRole('group', { name: 'A pagar' }).textContent).toBe(`A pagar${formatMoney(mzn('900.00'))}`);

    expect(cells(within(detail).getByRole('table', { name: 'Linhas' }))).toEqual([
      ['Frango à zambezianaPRATO-1', '2', formatMoney(mzn('450.00')), '—', formatMoney(mzn('900.00')), 'Ronda 1', 'Confirmada'],
      ['Sumo de maracujá · GrandePRATO-1', '1', formatMoney(mzn('0.00')), `Oferta ${formatMoney(mzn('120.00'))}`, formatMoney(mzn('120.00')), 'Ronda 1', 'Confirmada'],
      ['Pão com manteigaPRATO-1', '1', formatMoney(mzn('60.00')), '—', formatMoney(mzn('60.00')), '—', 'Rascunho'],
    ]);
    expect(cells(within(detail).getByRole('table', { name: 'Rondas' }))).toEqual([['Ronda 1', 'Enviada', '20:12', '2']]);
    expect(cells(within(detail).getByRole('table', { name: 'Descontos e ofertas' }))).toEqual([
      ['Sumo de maracujá', 'Oferta', '1', `${formatMoney(mzn('120.00'))} → ${formatMoney(mzn('0.00'))}`, 'Aniversário do cliente', '20:40'],
    ]);
  });

  it('opens the order named in the address, and closes it', async () => {
    const order = orderView(1043);
    ordersServer({ rows: [orderRow(1043)], detail: order });
    const { user, currentUrl } = renderApp(`${ORDERS}&orderId=${order.id}`);

    const detail = await screen.findByRole('region', { name: 'Pedido #1043' });
    await user.click(within(detail).getByRole('button', { name: 'Fechar detalhe' }));

    expect(screen.queryByRole('region', { name: 'Pedido #1043' })).not.toBeInTheDocument();
    expect(currentUrl()).not.toContain('orderId');
  });

  it("closes the open order when the user changes store: the other store's screen does not show it", async () => {
    const order = orderView(1043);
    ordersServer({ rows: [orderRow(1043)], detail: order });
    const { user, currentUrl } = renderApp(`${ORDERS}&orderId=${order.id}`);
    await screen.findByRole('region', { name: 'Pedido #1043' });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Loja' }), sommerschield.id);

    await waitFor(() => expect(currentUrl()).toContain(`storeId=${sommerschield.id}`));
    await waitFor(() => expect(currentUrl()).not.toContain('orderId'));
    expect(screen.queryByRole('region', { name: /^Pedido/ })).not.toBeInTheDocument();
  });

  it('says so when an order has no discount and no round yet', async () => {
    const order = orderView(7, { batches: [], lines: [] });
    ordersServer({ rows: [orderRow(7)], detail: order });

    renderApp(`${ORDERS}&orderId=${order.id}`);

    const detail = await screen.findByRole('region', { name: 'Pedido #7' });
    expect(within(detail).getByText('O pedido não tem linhas.')).toBeInTheDocument();
    expect(within(detail).getByText('Nenhuma ronda confirmada.')).toBeInTheDocument();
    expect(within(detail).getByText('Sem descontos nem ofertas.')).toBeInTheDocument();
  });
});
