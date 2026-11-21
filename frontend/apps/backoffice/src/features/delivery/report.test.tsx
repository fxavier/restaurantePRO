import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { delivery, joao, page, reportServer, tiago } from '../../test/delivery-fixtures';
import { api, baixa, problem } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

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

  it("counts the deliveries as the server totals them, never by summing on the client, and pages through them", async () => {
    const state = reportServer({ deliveries: [delivery(1, 'DELIVERED')], totalElements: 73 });
    const { user } = renderApp(REPORT);

    const total = await screen.findByRole('group', { name: 'Total de entregas' });
    await waitFor(() => expect(total).toHaveTextContent('73'));
    expect(screen.queryByText(/MZN|€/)).not.toBeInTheDocument();
    expect(screen.getByText('Página 1 de 2')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));

    expect(await screen.findByText('Página 2 de 2')).toBeInTheDocument();
    expect(state.queries.map((query) => query.get('page'))).toEqual(['0', '1']);
  });

  it('asks again for the chosen days and courier, and refuses a range that ends before it starts', async () => {
    const state = reportServer({ deliveries: [delivery(1, 'DELIVERED', { courierEmployeeId: joao.id })] });
    const { user } = renderApp(REPORT);
    await screen.findByRole('table', { name: 'Entregas' });

    const from = screen.getByLabelText('De');
    const to = screen.getByLabelText('Até');
    await user.clear(from);
    await user.type(from, '2026-11-22');
    expect(await screen.findByText('A data inicial é posterior à final.')).toBeInTheDocument();
    expect(state.queries).toHaveLength(1);

    await user.clear(to);
    await user.type(to, '2026-11-23');
    await user.selectOptions(await screen.findByLabelText('Estafeta'), joao.id!);

    await waitFor(() =>
      expect(Object.fromEntries(state.queries.at(-1)!)).toEqual({
        storeId: baixa.id,
        from: '2026-11-22',
        to: '2026-11-23',
        courierId: joao.id,
        page: '0',
        size: '50',
      }),
    );
    expect(screen.queryByText('A data inicial é posterior à final.')).not.toBeInTheDocument();
  });
});

describe('delivery report states', () => {
  it('asks for a store before listing anything', async () => {
    const state = reportServer();

    renderApp('/delivery/report');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver as suas entregas.')).toBeInTheDocument();
    expect(state.queries).toEqual([]);
  });

  it('says so when the period has no delivery', async () => {
    reportServer();

    renderApp(REPORT);

    expect(await screen.findByText('Não há entregas neste período.')).toBeInTheDocument();
  });

  it('shows the API refusal to a user without the permission in this store', async () => {
    reportServer();
    server.use(http.get(api('/api/v1/backoffice/deliveries'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp(REPORT);

    expect(await screen.findByText('Sem permissão para ver as entregas desta loja.')).toBeInTheDocument();
  });

  it('shows "Sem ligação" with a retry when the deliveries cannot be reached', async () => {
    let calls = 0;
    reportServer();
    server.use(
      http.get(api('/api/v1/backoffice/deliveries'), () => {
        calls += 1;
        return calls <= 3 ? problem(502, 'BACKEND_UNAVAILABLE') : HttpResponse.json(page([delivery(7, 'PENDING')]));
      }),
    );
    const { user } = renderApp(REPORT);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('cell', { name: '#7' })).toBeInTheDocument();
  });

  it('still lists the deliveries without the employee list, with neither courier names nor the courier filter', async () => {
    reportServer({ deliveries: [delivery(9, 'OUT_FOR_DELIVERY', { courierEmployeeId: joao.id })] });
    server.use(http.get(api('/api/v1/backoffice/employees'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp(REPORT);

    const table = await screen.findByRole('table', { name: 'Entregas' });
    expect(within(table).getByRole('cell', { name: 'Estafeta' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Estafeta')).not.toBeInTheDocument();
  });
});
