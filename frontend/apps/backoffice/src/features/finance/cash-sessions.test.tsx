import type { components } from '@restaurantpro/api-client';
import { formatMoney } from '@restaurantpro/ui';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, baixa } from '../../test/fixtures';
import { DAY_1, financeAdmin, financeServer, mzn, page, REGISTER_1 } from '../../test/finance-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

type SessionRow = components['schemas']['com.xavier.backend.cash.model.dto.SessionRow'];

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

/** The finance server, the store's employees and `answer` for every sessions read; each read's URL is recorded. */
function sessionsServer(answer: (query: URLSearchParams) => Response = () => HttpResponse.json(page([closed], 0, 20))) {
  const { requests } = financeServer();
  server.use(
    http.get(api(SESSIONS), ({ request }) => {
      const url = new URL(request.url);
      requests.push(url.pathname + url.search);
      return answer(url.searchParams);
    }),
    http.get(api('/api/v1/backoffice/employees'), () =>
      HttpResponse.json(page([{ id: OPERATOR, code: 'E01', name: 'Carlos Silva', active: true, locked: false, version: 0 }])),
    ),
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

  it('names the operator by id, without asking for the employees, to a user who may not read them', async () => {
    const requests = sessionsServer();
    server.use(
      http.get(api('/api/v1/auth/session'), () =>
        HttpResponse.json({ ...financeAdmin, permissions: financeAdmin.permissions.filter((p) => p !== 'PERM_EMPLOYEE_MANAGE') }),
      ),
      http.get(api('/api/v1/backoffice/employees'), ({ request }) => {
        requests.push(new URL(request.url).pathname);
        return HttpResponse.json(page([]));
      }),
    );
    renderApp(tab);

    expect(await screen.findByRole('row', { name: /CX1/ })).toHaveTextContent(OPERATOR);
    expect(requests).not.toContain('/api/v1/backoffice/employees');
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
});
