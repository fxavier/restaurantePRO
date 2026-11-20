import { screen, within } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { api, baixa, problem, signedIn } from '../../test/fixtures';
import { bar, copa, kitchen, productionHandlers, type ProductionState } from '../../test/production-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const PRODUCTION = `/production?storeId=${baixa.id}`;

function productionServer(initial: Partial<ProductionState> = {}, session?: Parameters<typeof signedIn>[0]) {
  const { state, handlers } = productionHandlers(initial);
  server.use(...signedIn(session));
  server.use(...handlers);
  return state;
}

describe('printers', () => {
  it("lists the selected store's printers with their address", async () => {
    productionServer();

    renderApp(PRODUCTION);

    const list = await screen.findByRole('list', { name: 'Impressoras da loja' });
    const kitchen = within(list).getByRole('listitem', { name: 'Cozinha Quentes' });
    expect(within(kitchen).getByText('192.168.1.181:9100')).toBeInTheDocument();
    expect(within(list).getByRole('listitem', { name: 'Bar Central' })).toBeInTheDocument();
  });

  it('asks for a store before reading anything', async () => {
    const state = productionServer();

    renderApp('/production');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver as suas impressoras.')).toBeInTheDocument();
    expect(state.requests).toEqual([]);
  });

  it('says so when the store has no printer yet', async () => {
    productionServer({ printers: [] });

    renderApp(PRODUCTION);

    expect(await screen.findByText('Esta loja ainda não tem impressoras.')).toBeInTheDocument();
  });

  it('shows "Sem ligação" with a retry when the printers cannot be reached', async () => {
    let calls = 0;
    productionServer();
    server.use(
      http.get(api('/api/v1/backoffice/printers'), () => {
        calls += 1;
        return calls <= 3 ? problem(502, 'BACKEND_UNAVAILABLE') : undefined;
      }),
    );

    const { user } = renderApp(PRODUCTION);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('listitem', { name: 'Cozinha Quentes' })).toBeInTheDocument();
  });

  it('shows the API refusal to a user without the permission in this store', async () => {
    productionServer();
    server.use(http.get(api('/api/v1/backoffice/printers'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp(PRODUCTION);

    expect(await screen.findByText('Sem permissão para ver as impressoras desta loja.')).toBeInTheDocument();
  });

  it("shows each printer's mode by label, where a redirect goes, and what is routed to it", async () => {
    productionServer({
      printers: [kitchen, { ...bar, mode: 'REDIRECT', redirectPrinterId: kitchen.id }, { ...copa, active: false, mode: 'HOLD' }],
    });

    renderApp(PRODUCTION);

    const kitchenCard = await screen.findByRole('listitem', { name: 'Cozinha Quentes' });
    expect(within(kitchenCard).getByText('Normal')).toBeInTheDocument();
    expect(within(kitchenCard).getByText('COZINHA')).toBeInTheDocument();
    const barCard = screen.getByRole('listitem', { name: 'Bar Central' });
    expect(within(barCard).getByText('Redirecionar')).toBeInTheDocument();
    expect(within(barCard).getByText('Envia para Cozinha Quentes')).toBeInTheDocument();
    expect(within(barCard).getByText('Caixa 1')).toBeInTheDocument();
    const copaCard = screen.getByRole('listitem', { name: 'Copa' });
    expect(within(copaCard).getByText('Em espera')).toBeInTheDocument();
    expect(within(copaCard).getByText('Inativa')).toBeInTheDocument();
    expect(within(copaCard).getByText('Sem destinos encaminhados.')).toBeInTheDocument();
  });
});
