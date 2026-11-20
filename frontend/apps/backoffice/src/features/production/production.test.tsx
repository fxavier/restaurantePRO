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

describe('adding a printer', () => {
  it("sends the printer with the chosen profile's settings and shows it in the list", async () => {
    const state = productionServer();
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Adicionar impressora' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar impressora' });
    await user.type(within(dialog).getByLabelText('Código'), 'copa');
    await user.type(within(dialog).getByLabelText('Nome'), 'Copa');
    await user.type(within(dialog).getByLabelText('Endereço IP'), '192.168.1.182');
    await user.selectOptions(within(dialog).getByLabelText('Perfil'), 'GENERIC_58');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));

    expect(await screen.findByRole('listitem', { name: 'Copa' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const sent = state.requests.find((request) => request.method === 'POST');
    expect(sent).toEqual({
      method: 'POST',
      url: '/api/v1/backoffice/printers',
      idempotencyKey: null,
      body: {
        storeId: baixa.id,
        code: 'COPA',
        name: 'Copa',
        host: '192.168.1.182',
        port: 9100,
        codePage: 'CP437',
        paperWidthMm: 58,
        columns: 32,
        cutMode: 'NONE',
        feedLines: 4,
        statusProbe: true,
        drawerPulseMs: 100,
      },
    });
  });

  it('checks the fields before sending, and puts a taken code on the code field', async () => {
    const state = productionServer();
    server.use(
      http.post(api('/api/v1/backoffice/printers'), () => problem(409, 'PRINTER_CODE_TAKEN', 'Conflict')),
    );
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Adicionar impressora' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar impressora' });
    await user.clear(within(dialog).getByLabelText('Porta'));
    await user.type(within(dialog).getByLabelText('Porta'), '70000');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));

    expect(await within(dialog).findByText('Indique o código.')).toBeInTheDocument();
    expect(within(dialog).getByText('Indique o endereço da impressora.')).toBeInTheDocument();
    expect(within(dialog).getByText('A porta vai de 1 a 65535.')).toBeInTheDocument();
    expect(state.requests.filter((request) => request.method === 'POST')).toEqual([]);

    await user.type(within(dialog).getByLabelText('Código'), 'COZ');
    await user.type(within(dialog).getByLabelText('Nome'), 'Outra');
    await user.type(within(dialog).getByLabelText('Endereço IP'), '192.168.1.190');
    await user.clear(within(dialog).getByLabelText('Porta'));
    await user.type(within(dialog).getByLabelText('Porta'), '9100');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));

    expect(await within(dialog).findByText('Já existe uma impressora com este código nesta loja.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'true');
  });
});
