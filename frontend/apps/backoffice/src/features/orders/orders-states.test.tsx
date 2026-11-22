import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa, problem, signedIn } from '../../test/fixtures';
import { orderRow, ordersServer, orderView, page } from '../../test/orders-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const ORDERS = `/orders?storeId=${baixa.id}`;

describe('orders states', () => {
  it('asks for a store before searching anything', async () => {
    const state = ordersServer();

    renderApp('/orders');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver os seus pedidos.')).toBeInTheDocument();
    expect(state.queries).toEqual([]);
  });

  it('shows a loading state, then says so when nothing matches', async () => {
    ordersServer();

    renderApp(ORDERS);

    expect(await screen.findByText('Nenhum pedido corresponde à pesquisa.')).toBeInTheDocument();
  });

  it('shows the API refusal to a user without the permission in this store', async () => {
    ordersServer();
    server.use(http.get(api('/api/v1/backoffice/orders'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp(ORDERS);

    expect(await screen.findByText('Sem permissão para ver os pedidos desta loja.')).toBeInTheDocument();
  });

  it('shows "Sem ligação" with a retry when the orders cannot be reached', async () => {
    let calls = 0;
    ordersServer();
    server.use(
      http.get(api('/api/v1/backoffice/orders'), () => {
        calls += 1;
        return calls <= 3 ? problem(502, 'BACKEND_UNAVAILABLE') : HttpResponse.json(page([orderRow(7)]));
      }),
    );
    const { user } = renderApp(ORDERS);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('button', { name: 'Ver pedido #7' })).toBeInTheDocument();
  });

  it('says so when the order in the address does not exist, and keeps the search usable', async () => {
    ordersServer({ rows: [orderRow(1043)] });
    server.use(http.get(api('/api/v1/backoffice/orders/:id'), () => problem(404, 'ORDER_NOT_FOUND', 'Not found')));

    renderApp(`${ORDERS}&orderId=${orderView(9).id}`);

    const detail = await screen.findByRole('region', { name: 'Pedido' });
    expect(await within(detail).findByText('Este pedido não existe nesta empresa.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ver pedido #1043' })).toBeInTheDocument();
  });

  it('hides the area from a user without ORDER_VIEW', async () => {
    server.use(...signedIn({ session: { ...admin, permissions: ['PERM_CATALOG_MANAGE'] } }));

    renderApp();

    const areas = await screen.findByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: 'Catálogo' })).toBeInTheDocument();
    expect(within(areas).queryByRole('link', { name: 'Pedidos' })).not.toBeInTheDocument();
  });
});
