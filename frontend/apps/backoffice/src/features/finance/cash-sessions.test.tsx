import type { components } from '@restaurantpro/api-client';
import { formatMoney } from '@restaurantpro/ui';
import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, baixa } from '../../test/fixtures';
import { DAY_1, financeAdmin, financeServer, mzn, page, REGISTER_1 } from '../../test/finance-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

type SessionRow = components['schemas']['com.xavier.backend.cash.model.dto.SessionRow'];
type AdjustmentRow = components['schemas']['com.xavier.backend.cash.model.dto.AdjustmentRow'];

const SESSIONS = '/api/v1/backoffice/cash/sessions';
const OPERATOR = '0e0e0000-0000-4000-8000-000000000001';
const tab = `/finance?storeId=${baixa.id}&tab=sessoes`;
const money = (amount: string) => formatMoney(mzn(amount)).replace(/\s/g, ' ');

function session(id: string, extra: Partial<SessionRow> = {}): SessionRow {
  return {
    id,
    storeId: baixa.id,
    registerId: REGISTER_1,
    registerCode: 'CX1',
    cashDayId: DAY_1,
    businessDate: '2026-11-19',
    operatorEmployeeId: OPERATOR,
    operatorName: 'Carlos Silva',
    status: 'CLOSED',
    openingFloat: mzn('150.00'),
    openedAt: '2026-11-19T09:30:00Z',
    closedAt: '2026-11-19T12:38:00Z',
    closedByEmployeeId: OPERATOR,
    closeKind: 'OPERATOR',
    counted: [
      { methodCode: 'CASH', amount: mzn('590.50') },
      { methodCode: 'CARD', amount: mzn('1200.00') },
    ],
    ...extra,
  };
}

const closed = session('5e550000-0000-4000-8000-000000000001');

/** The finance server and `answer` for every sessions read; each read's URL is recorded, the employees read too. */
function sessionsServer(answer: (query: URLSearchParams) => Response = () => HttpResponse.json(page([closed], 0, 20))) {
  const { requests } = financeServer();
  server.use(
    http.get(api(SESSIONS), ({ request }) => {
      const url = new URL(request.url);
      requests.push(url.pathname + url.search);
      return answer(url.searchParams);
    }),
    http.get(api('/api/v1/backoffice/employees'), ({ request }) => {
      requests.push(new URL(request.url).pathname);
      return HttpResponse.json(page([]));
    }),
  );
  return requests;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-19T22:30:00Z'));
  sessionStorage.clear();
});
afterEach(() => vi.useRealTimers());

describe('cash sessions', () => {
  it("lists the period's sessions with their operator, times, float and blind count, as the server sent them", async () => {
    const requests = sessionsServer();
    renderApp(tab);

    const table = await screen.findByRole('table', { name: 'Sessões de caixa' });
    const row = await within(table).findByRole('row', { name: /CX1/ });
    expect(row).toHaveTextContent('Carlos Silva');
    expect(row).toHaveTextContent('19/11/26, 11:30');
    expect(row).toHaveTextContent('19/11/26, 14:38');
    expect(row).toHaveTextContent('Fechada');
    expect(row).toHaveTextContent('Pelo operador');
    expect(row).toHaveTextContent(money('150.00'));
    expect(row).toHaveTextContent(`CASH${money('590.50')}`);
    expect(row).toHaveTextContent(`CARD${money('1200.00')}`);
    expect(requests).toContain(`${SESSIONS}?storeId=${baixa.id}&from=2026-11-14&to=2026-11-20&page=0&size=20`);
  });

  it('asks the server for the open sessions only, and shows one as open, not yet closed nor counted', async () => {
    const open = session('5e550000-0000-4000-8000-000000000002', { status: 'OPEN', closedAt: null, closedByEmployeeId: null, closeKind: null, counted: [] });
    const requests = sessionsServer((query) => HttpResponse.json(page(query.get('status') === 'OPEN' ? [open] : [closed], 0, 20)));
    const { user } = renderApp(tab);

    await screen.findByRole('row', { name: /Fechada/ });
    await user.selectOptions(screen.getByLabelText('Estado das sessões'), 'OPEN');

    const row = await screen.findByRole('row', { name: /Aberta/ });
    expect(row).not.toHaveTextContent('Pelo operador');
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    expect(requests).toContain(`${SESSIONS}?storeId=${baixa.id}&from=2026-11-14&to=2026-11-20&status=OPEN&page=0&size=20`);
  });

  it('pages on the server, and goes back to the first page when the period changes', async () => {
    const second = session('5e550000-0000-4000-8000-000000000003', { registerCode: 'CX2' });
    const requests = sessionsServer((query) => {
      const index = Number(query.get('page'));
      return HttpResponse.json(page(index === 0 ? [closed] : [second], index, 20, 21));
    });
    const { user } = renderApp(tab);

    await screen.findByRole('row', { name: /CX1/ });
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));
    expect(await screen.findByRole('row', { name: /CX2/ })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Período'), '1');

    expect(await screen.findByRole('row', { name: /CX1/ })).toBeInTheDocument();
    expect(requests).toContain(`${SESSIONS}?storeId=${baixa.id}&from=2026-11-20&to=2026-11-20&page=0&size=20`);
  });

  it('keeps the status filter when the period changes, going back to the first page only', async () => {
    const requests = sessionsServer((query) => HttpResponse.json(page([closed], Number(query.get('page')), 20, 21)));
    const { user } = renderApp(tab);

    await screen.findByRole('row', { name: /CX1/ });
    await user.selectOptions(screen.getByLabelText('Estado das sessões'), 'CLOSED');
    await user.click(await screen.findByRole('button', { name: 'Seguinte' }));
    await waitFor(() => expect(requests).toContain(`${SESSIONS}?storeId=${baixa.id}&from=2026-11-14&to=2026-11-20&status=CLOSED&page=1&size=20`));
    await user.selectOptions(screen.getByLabelText('Período'), '1');

    await waitFor(() => expect(requests).toContain(`${SESSIONS}?storeId=${baixa.id}&from=2026-11-20&to=2026-11-20&status=CLOSED&page=0&size=20`));
    expect(screen.getByLabelText('Estado das sessões')).toHaveValue('CLOSED');
  });

  it('names the operator from the row, without reading the employees, to a user who may not read them', async () => {
    const requests = sessionsServer();
    server.use(
      http.get(api('/api/v1/auth/session'), () =>
        HttpResponse.json({ ...financeAdmin, permissions: financeAdmin.permissions.filter((p) => p !== 'PERM_EMPLOYEE_MANAGE') }),
      ),
    );
    renderApp(tab);

    expect(await screen.findByRole('row', { name: /CX1/ })).toHaveTextContent('Carlos Silva');
    expect(requests).not.toContain('/api/v1/backoffice/employees');
  });

  it('names the operator by id when the server knows no name for them', async () => {
    sessionsServer(() => HttpResponse.json(page([session('5e550000-0000-4000-8000-000000000004', { operatorName: null })], 0, 20)));
    renderApp(tab);

    expect(await screen.findByRole('row', { name: /CX1/ })).toHaveTextContent(OPERATOR);
  });

  it('says the sessions are unavailable without a connection, and reads them again on retry', async () => {
    let fail = true;
    sessionsServer(() => (fail ? HttpResponse.error() : HttpResponse.json(page([closed], 0, 20))));
    const { user } = renderApp(tab);

    const retry = await screen.findByRole('button', { name: /tentar novamente/i });
    fail = false;
    await user.click(retry);

    expect(await screen.findByRole('row', { name: /CX1/ })).toBeInTheDocument();
  });

  it('says when no session was opened in the period', async () => {
    sessionsServer(() => HttpResponse.json(page([], 0, 20)));
    renderApp(tab);
    expect(await screen.findByText('Sem sessões de caixa neste período.')).toBeInTheDocument();
  });

  describe('adjustments', () => {
    const ADJUSTMENTS = `${SESSIONS}/${closed.id}/adjustments`;
    const adjustment = (id: string, extra: Partial<AdjustmentRow>): AdjustmentRow => ({
      id,
      sessionId: closed.id,
      direction: 'IN',
      methodCode: 'CASH',
      amount: mzn('2.50'),
      reason: 'Moeda encontrada',
      occurredAt: '2026-11-19T15:00:00Z',
      ...extra,
    });

    function adjustmentsServer(answer: () => Response) {
      const requests = sessionsServer();
      server.use(
        http.get(api(ADJUSTMENTS), ({ request }) => {
          requests.push(new URL(request.url).pathname);
          return answer();
        }),
      );
      return requests;
    }

    it("shows a closed session's adjustments in the order the server sent them, with direction, amount and reason", async () => {
      const requests = adjustmentsServer(() =>
        HttpResponse.json([
          adjustment('ad000000-0000-4000-8000-000000000002', {}),
          adjustment('ad000000-0000-4000-8000-000000000001', {
            direction: 'OUT',
            methodCode: 'CARD',
            amount: mzn('5.00'),
            reason: 'Despesa de pão por registar',
            occurredAt: '2026-11-19T14:00:00Z',
          }),
        ]),
      );
      const { user } = renderApp(tab);

      const toggle = await screen.findByRole('button', { name: 'Ver ajustes da sessão CX1' });
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      await user.click(toggle);

      const table = await screen.findByRole('table', { name: /Ajustes da sessão CX1/ });
      const rows = within(table).getAllByRole('row').slice(1);
      expect(rows).toHaveLength(2);
      expect(rows[0]).toHaveTextContent('19/11/26, 17:00');
      expect(rows[0]).toHaveTextContent('Entrada');
      expect(rows[0]).toHaveTextContent(`CASH`);
      expect(rows[0]).toHaveTextContent(money('2.50'));
      expect(rows[0]).toHaveTextContent('Moeda encontrada');
      expect(rows[1]).toHaveTextContent('Saída');
      expect(rows[1]).toHaveTextContent('CARD');
      expect(rows[1]).toHaveTextContent(money('5.00'));
      expect(rows[1]).toHaveTextContent('Despesa de pão por registar');
      expect(screen.getByRole('button', { name: 'Ocultar ajustes da sessão CX1' })).toHaveAttribute('aria-expanded', 'true');
      expect(requests).toContain(ADJUSTMENTS);
    });

    it('says when a session has no adjustments', async () => {
      adjustmentsServer(() => HttpResponse.json([]));
      const { user } = renderApp(tab);

      await user.click(await screen.findByRole('button', { name: 'Ver ajustes da sessão CX1' }));

      expect(await screen.findByText('Sem ajustes nesta sessão.')).toBeInTheDocument();
    });

    it('says the adjustments are unavailable without a connection, never "no adjustments", and reads them again on retry', async () => {
      let fail = true;
      adjustmentsServer(() => (fail ? HttpResponse.error() : HttpResponse.json([adjustment('ad000000-0000-4000-8000-000000000004', {})])));
      const { user } = renderApp(tab);

      await user.click(await screen.findByRole('button', { name: 'Ver ajustes da sessão CX1' }));
      const retry = await screen.findByRole('button', { name: /tentar novamente/i });
      expect(screen.queryByText('Sem ajustes nesta sessão.')).not.toBeInTheDocument();
      fail = false;
      await user.click(retry);

      expect(await screen.findByRole('table', { name: /Ajustes da sessão CX1/ })).toBeInTheDocument();
    });

    it('offers no adjustments for an open session, which takes none', async () => {
      const open = session('5e550000-0000-4000-8000-000000000002', { registerCode: 'CX9', status: 'OPEN', closedAt: null, closedByEmployeeId: null, closeKind: null, counted: [] });
      sessionsServer(() => HttpResponse.json(page([open], 0, 20)));
      renderApp(tab);

      await screen.findByRole('row', { name: /CX9/ });
      expect(screen.queryByRole('button', { name: /ajustes da sessão/ })).not.toBeInTheDocument();
    });

    it('hides the adjustments again', async () => {
      adjustmentsServer(() => HttpResponse.json([adjustment('ad000000-0000-4000-8000-000000000003', {})]));
      const { user } = renderApp(tab);

      await user.click(await screen.findByRole('button', { name: 'Ver ajustes da sessão CX1' }));
      await screen.findByRole('table', { name: /Ajustes da sessão CX1/ });
      await user.click(screen.getByRole('button', { name: 'Ocultar ajustes da sessão CX1' }));

      expect(screen.queryByRole('table', { name: /Ajustes da sessão CX1/ })).not.toBeInTheDocument();
    });
  });
});
