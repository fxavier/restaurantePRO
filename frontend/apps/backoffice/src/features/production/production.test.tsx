import { screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa, problem, signedIn } from '../../test/fixtures';
import {
  bar,
  caixa,
  copa,
  job,
  kitchen,
  productionHandlers,
  sala,
  ticket,
  type ProductionState,
} from '../../test/production-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const PRODUCTION = `/production?storeId=${baixa.id}`;
const A_KEY = expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

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

describe('configuring a printer', () => {
  it('sends the changed printer at the version it was read, and shows the answer', async () => {
    const state = productionServer();
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Configurar Bar Central' }));
    const dialog = await screen.findByRole('dialog', { name: 'Configurar Bar Central' });
    expect(within(dialog).getByLabelText('Endereço IP')).toHaveValue('192.168.1.183');
    await user.clear(within(dialog).getByLabelText('Nome'));
    await user.type(within(dialog).getByLabelText('Nome'), 'Bar Principal');
    await user.click(within(dialog).getByRole('checkbox', { name: 'Ativa' }));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    const card = await screen.findByRole('listitem', { name: 'Bar Principal' });
    expect(within(card).getByText('Inativa')).toBeInTheDocument();
    expect(state.requests.find((request) => request.method === 'PUT')).toEqual({
      method: 'PUT',
      url: `/api/v1/backoffice/printers/${bar.id}`,
      idempotencyKey: null,
      body: { code: 'BAR', name: 'Bar Principal', host: '192.168.1.183', port: 9100, codePage: 'CP860', active: false, version: 3 },
    });
  });

  it('says another person changed it and reads the printers again on a version conflict', async () => {
    const state = productionServer();
    server.use(
      http.put(api('/api/v1/backoffice/printers/:id'), () => problem(409, 'VERSION_CONFLICT', 'Conflict')),
    );
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Configurar Bar Central' }));
    const dialog = await screen.findByRole('dialog', { name: 'Configurar Bar Central' });
    const reads = state.requests.filter((request) => request.url.startsWith('/api/v1/backoffice/printers?')).length;
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByText('Foi alterado por outra pessoa. Recarregue e tente de novo.')).toBeInTheDocument();
    await waitFor(() =>
      expect(state.requests.filter((request) => request.url.startsWith('/api/v1/backoffice/printers?')).length).toBe(reads + 1),
    );
  });
});

describe("a printer's mode", () => {
  it('redirects to another printer of the store, at the version it was read', async () => {
    const state = productionServer();
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Modo de Cozinha Quentes' }));
    const dialog = await screen.findByRole('dialog', { name: 'Modo de Cozinha Quentes' });
    expect(within(dialog).queryByRole('radio', { name: /Ignorar/ })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('radio', { name: /Redirecionar/ }));
    await user.selectOptions(within(dialog).getByLabelText('Enviar para'), 'Bar Central');
    await user.click(within(dialog).getByRole('button', { name: 'Mudar modo' }));

    const card = await screen.findByRole('listitem', { name: 'Cozinha Quentes' });
    expect(await within(card).findByText('Envia para Bar Central')).toBeInTheDocument();
    expect(state.requests.find((request) => request.method === 'PUT')).toMatchObject({
      url: `/api/v1/backoffice/printers/${kitchen.id}/mode`,
      body: { mode: 'REDIRECT', redirectPrinterId: bar.id, version: 3 },
    });
  });

  it('offers Ignorar only to who may suppress printing in this store', async () => {
    productionServer({}, { session: { ...admin, permissions: [...admin.permissions!, `PERM_PRODUCTION_SUPPRESS@${baixa.id}`] } });
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Modo de Cozinha Quentes' }));
    const dialog = await screen.findByRole('dialog', { name: 'Modo de Cozinha Quentes' });
    expect(within(dialog).getByRole('radio', { name: /Ignorar/ })).toBeInTheDocument();
    expect(within(dialog).getByRole('radio', { name: /Normal/ })).toBeChecked();
  });
});

const OPERATOR = { session: { ...admin, permissions: [...admin.permissions!, `PERM_PRODUCTION_OPERATE@${baixa.id}`] } };
const sent = job('dddd0000-0000-4000-8000-000000000001', { sourceType: 'BATCH', destination: 'COZINHA', status: 'SENT' });
const failed = job('dddd0000-0000-4000-8000-000000000002', {
  sourceType: 'SUBTOTAL',
  destination: undefined,
  printerId: bar.id,
  status: 'FAILED',
  attempts: 5,
  lastError: 'Connection refused',
  createdAt: '2026-11-20T12:11:02Z',
});
const unknown = job('dddd0000-0000-4000-8000-000000000003', { kind: 'COPY', status: 'UNKNOWN', createdAt: '2026-11-20T12:06:50Z' });

describe('the print queue', () => {
  it("lists the store's jobs newest first, each with its printer, state and time in the store's timezone", async () => {
    const state = productionServer({ jobs: [sent, failed, unknown] });

    renderApp(PRODUCTION);

    const queue = await screen.findByRole('list', { name: 'Trabalhos de impressão' });
    const items = await within(queue).findAllByRole('listitem');
    expect(items.map((item) => item.getAttribute('aria-label'))).toEqual([
      'Pedido · COZINHA',
      'Consulta de mesa',
      'Pedido · COZINHA',
    ]);
    expect(within(items[0]!).getByText('Enviado')).toBeInTheDocument();
    expect(within(items[0]!).getByText('Cozinha Quentes')).toBeInTheDocument();
    expect(within(items[0]!).getByText('14:15')).toBeInTheDocument();
    expect(within(items[1]!).getByText('Falhou')).toBeInTheDocument();
    expect(within(items[1]!).getByText('Connection refused')).toBeInTheDocument();
    // An unknown outcome is never shown as a failure (§7, §8.3).
    expect(within(items[2]!).getByText('Por confirmar')).toBeInTheDocument();
    expect(within(items[2]!).getByText('Cópia')).toBeInTheDocument();
    expect(state.requests.map((request) => request.url)).toContain(
      `/api/v1/backoffice/print-jobs?storeId=${baixa.id}&page=0&size=20`,
    );
  });

  it('filters by state on the server', async () => {
    const state = productionServer({ jobs: [sent, failed, unknown] });
    const { user } = renderApp(PRODUCTION);

    await user.selectOptions(await screen.findByLabelText('Estado dos trabalhos'), 'Falhados');

    const queue = screen.getByRole('list', { name: 'Trabalhos de impressão' });
    await waitFor(() => expect(within(queue).getAllByRole('listitem')).toHaveLength(1));
    expect(state.requests.map((request) => request.url)).toContain(
      `/api/v1/backoffice/print-jobs?storeId=${baixa.id}&status=FAILED&page=0&size=20`,
    );
  });

  it('says so when nothing was printed yet', async () => {
    productionServer();

    renderApp(PRODUCTION);

    expect(await screen.findByText('Sem trabalhos de impressão.')).toBeInTheDocument();
  });

  it('offers no retry or acknowledgement to who may not operate production', async () => {
    productionServer({ jobs: [failed, unknown] });

    renderApp(PRODUCTION);

    const queue = await screen.findByRole('list', { name: 'Trabalhos de impressão' });
    await within(queue).findAllByRole('listitem');
    expect(within(queue).queryByRole('button')).not.toBeInTheDocument();
  });

  it('retries a failed job as a new job, under one key, and shows the queue again', async () => {
    const state = productionServer({ jobs: [failed] }, OPERATOR);
    const { user } = renderApp(PRODUCTION);

    const queue = await screen.findByRole('list', { name: 'Trabalhos de impressão' });
    const item = await within(queue).findByRole('listitem', { name: 'Consulta de mesa' });
    await user.click(within(item).getByRole('button', { name: 'Reenviar' }));

    expect(await within(queue).findByText('Em fila')).toBeInTheDocument();
    expect(within(queue).getByText('Reenviado')).toBeInTheDocument();
    const retry = state.requests.find((request) => request.method === 'POST');
    expect(retry).toMatchObject({ url: `/api/v1/backoffice/print-jobs/${failed.id}/retry`, idempotencyKey: A_KEY });
  });

  it('acknowledges a job of unknown outcome as printed, without a new print', async () => {
    const state = productionServer({ jobs: [unknown] }, OPERATOR);
    const { user } = renderApp(PRODUCTION);

    const queue = await screen.findByRole('list', { name: 'Trabalhos de impressão' });
    const item = await within(queue).findByRole('listitem', { name: 'Pedido · COZINHA' });
    await user.click(within(item).getByRole('button', { name: 'Confirmar impresso' }));

    expect(await within(queue).findByText('Confirmado como impresso')).toBeInTheDocument();
    expect(within(queue).queryByRole('button', { name: 'Reenviar' })).not.toBeInTheDocument();
    expect(state.requests.filter((request) => request.method === 'POST')).toEqual([
      expect.objectContaining({ url: `/api/v1/backoffice/print-jobs/${unknown.id}/acknowledge`, idempotencyKey: A_KEY }),
    ]);
  });

  it('explains a job someone else already resolved, and reads the queue again', async () => {
    const state = productionServer({ jobs: [failed] }, OPERATOR);
    server.use(
      http.post(api('/api/v1/backoffice/print-jobs/:id/retry'), () => problem(409, 'PRINT_JOB_ALREADY_RESOLVED', 'Conflict')),
    );
    const { user } = renderApp(PRODUCTION);

    const queue = await screen.findByRole('list', { name: 'Trabalhos de impressão' });
    const item = await within(queue).findByRole('listitem', { name: 'Consulta de mesa' });
    const reads = state.requests.filter((request) => request.url.includes('print-jobs?')).length;
    await user.click(within(item).getByRole('button', { name: 'Reenviar' }));

    expect(await screen.findByText('Este trabalho já foi resolvido por outra pessoa. A fila foi atualizada.')).toBeInTheDocument();
    await waitFor(() =>
      expect(state.requests.filter((request) => request.url.includes('print-jobs?')).length).toBeGreaterThan(reads),
    );
  });
});

describe('routing by destination', () => {
  it("replaces the store's routes with the edited table, and the printers show what they now receive", async () => {
    const state = productionServer();
    const { user } = renderApp(PRODUCTION);

    const routes = await screen.findByRole('form', { name: 'Rotas por destino' });
    expect(await within(routes).findByDisplayValue('COZINHA')).toBeInTheDocument();
    await user.click(within(routes).getByRole('button', { name: 'Adicionar destino' }));
    await user.type(within(routes).getByLabelText('Destino 2'), 'bebidas');
    await user.selectOptions(within(routes).getByLabelText('Impressora do destino 2'), 'Bar Central');
    await user.click(within(routes).getByRole('button', { name: 'Guardar rotas' }));

    const barCard = await screen.findByRole('listitem', { name: 'Bar Central' });
    expect(await within(barCard).findByText('BEBIDAS')).toBeInTheDocument();
    expect(state.requests.find((request) => request.url === '/api/v1/backoffice/production-routes')).toMatchObject({
      method: 'PUT',
      body: {
        storeId: baixa.id,
        routes: [
          { destinationCode: 'COZINHA', printerId: kitchen.id },
          { destinationCode: 'BEBIDAS', printerId: bar.id },
        ],
      },
    });
  });

  it('refuses a destination twice or without a printer before sending, and removes a row', async () => {
    const state = productionServer();
    const { user } = renderApp(PRODUCTION);

    const routes = await screen.findByRole('form', { name: 'Rotas por destino' });
    await within(routes).findByDisplayValue('COZINHA');
    await user.click(within(routes).getByRole('button', { name: 'Adicionar destino' }));
    await user.type(within(routes).getByLabelText('Destino 2'), 'cozinha');
    await user.click(within(routes).getByRole('button', { name: 'Guardar rotas' }));

    expect(await within(routes).findByText('Este destino já tem rota.')).toBeInTheDocument();
    expect(within(routes).getByText('Escolha a impressora.')).toBeInTheDocument();
    expect(state.requests.filter((request) => request.method === 'PUT')).toEqual([]);

    await user.click(within(routes).getByRole('button', { name: 'Remover destino 2' }));
    await user.click(within(routes).getByRole('button', { name: 'Remover destino 1' }));
    await user.click(within(routes).getByRole('button', { name: 'Guardar rotas' }));
    await waitFor(() =>
      expect(state.requests.find((request) => request.method === 'PUT')?.body).toEqual({ storeId: baixa.id, routes: [] }),
    );
  });

  it('explains routes changed by someone else meanwhile, and reads them again', async () => {
    productionServer();
    server.use(
      http.put(api('/api/v1/backoffice/production-routes'), () => problem(409, 'ROUTES_CHANGED_CONCURRENTLY', 'Conflict')),
    );
    const { user } = renderApp(PRODUCTION);

    const routes = await screen.findByRole('form', { name: 'Rotas por destino' });
    await within(routes).findByDisplayValue('COZINHA');
    await user.click(within(routes).getByRole('button', { name: 'Guardar rotas' }));

    expect(
      await within(routes).findByText('O encaminhamento foi alterado por outra pessoa. Reveja e guarde de novo.'),
    ).toBeInTheDocument();
  });
});

describe('printers of the terminals', () => {
  it("replaces the store's terminal → printer table", async () => {
    const state = productionServer();
    const { user } = renderApp(PRODUCTION);

    const form = await screen.findByRole('form', { name: 'Impressora por terminal' });
    await waitFor(() => expect(within(form).getByLabelText('Caixa 1')).toHaveDisplayValue('Bar Central'));
    expect(within(form).getByLabelText('Sala')).toHaveDisplayValue('Sem impressora');
    await user.selectOptions(within(form).getByLabelText('Sala'), 'Cozinha Quentes');
    await user.click(within(form).getByRole('button', { name: 'Guardar terminais' }));

    await waitFor(() =>
      expect(state.requests.find((request) => request.url === '/api/v1/backoffice/terminal-printers')).toMatchObject({
        method: 'PUT',
        body: {
          storeId: baixa.id,
          assignments: [
            { terminalId: caixa.id, printerId: bar.id },
            { terminalId: sala.id, printerId: kitchen.id },
          ],
        },
      }),
    );
    const kitchenCard = screen.getByRole('listitem', { name: 'Cozinha Quentes' });
    expect(await within(kitchenCard).findByText('Sala')).toBeInTheDocument();
  });
});

const unrouted = ticket('eeee0000-0000-4000-8000-000000000009', { destination: 'SOBREMESAS', lines: ['1x Pudim', '2x Mousse'] });

describe('the summary', () => {
  it('counts the active printers and, from the server, the jobs and tickets that need someone', async () => {
    productionServer({
      printers: [kitchen, bar, { ...copa, active: false }],
      jobs: [sent, failed, unknown],
      tickets: [unrouted, ticket('eeee0000-0000-4000-8000-000000000008', { routing: 'ROUTED' })],
    });

    renderApp(PRODUCTION);

    const summary = await screen.findByRole('region', { name: 'Resumo' });
    await waitFor(() =>
      expect(within(summary).getAllByRole('definition').map((value) => value.textContent)).toEqual(['2 / 3', '1', '1', '1']),
    );
    expect(within(summary).getAllByRole('term').map((term) => term.textContent)).toEqual([
      'Impressoras ativas',
      'Trabalhos falhados',
      'Por confirmar',
      'Talões por encaminhar',
    ]);
  });
});

describe('tickets with no route', () => {
  it('lists them and sends one to its printer once a route exists, under one key', async () => {
    const state = productionServer({ tickets: [unrouted] }, OPERATOR);
    const { user } = renderApp(PRODUCTION);

    const list = await screen.findByRole('list', { name: 'Talões por encaminhar' });
    const item = await within(list).findByRole('listitem', { name: 'Pedido · SOBREMESAS' });
    expect(within(item).getByText('1x Pudim, 2x Mousse')).toBeInTheDocument();
    await user.click(within(item).getByRole('button', { name: 'Encaminhar' }));

    expect(await screen.findByText('Todos os talões têm rota.')).toBeInTheDocument();
    expect(state.requests.find((request) => request.method === 'POST')).toMatchObject({
      url: `/api/v1/backoffice/production-tickets/${unrouted.id}/route`,
      idempotencyKey: A_KEY,
    });
    expect(state.requests.map((request) => request.url)).toContain(
      `/api/v1/backoffice/production-tickets?storeId=${baixa.id}&routing=UNROUTED&page=0&size=20`,
    );
  });

  it('says the ticket still has no route', async () => {
    productionServer({ tickets: [unrouted] }, OPERATOR);
    server.use(
      http.post(api('/api/v1/backoffice/production-tickets/:id/route'), () => problem(422, 'STILL_UNROUTED', 'Unprocessable')),
    );
    const { user } = renderApp(PRODUCTION);

    const item = await screen.findByRole('listitem', { name: 'Pedido · SOBREMESAS' });
    await user.click(within(item).getByRole('button', { name: 'Encaminhar' }));

    expect(
      await screen.findByText('O talão ainda não tem rota: configure uma rota para o seu destino primeiro.'),
    ).toBeInTheDocument();
  });
});

describe('a test page', () => {
  it('is one intent: "Sem ligação" sends it again under the same key, then says it is queued', async () => {
    const state = productionServer();
    const keys: (string | null)[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/printers/:id/test'), ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        return keys.length === 1 ? problem(503, 'BACKEND_UNAVAILABLE') : undefined;
      }),
    );
    const { user } = renderApp(PRODUCTION);

    await user.click(await screen.findByRole('button', { name: 'Imprimir teste em Cozinha Quentes' }));
    expect(await screen.findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByText('Página de teste de Cozinha Quentes enviada para a fila.')).toBeInTheDocument();
    expect(keys).toEqual([A_KEY, keys[0]]);
    expect(state.requests.filter((request) => request.url.endsWith('/test'))).toHaveLength(1);
  });
});
