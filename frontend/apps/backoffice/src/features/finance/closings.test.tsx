import { formatMoney } from '@restaurantpro/ui';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { savePendingCommand } from '../../lib/step-up';
import { admin, api, baixa, companyA, problem, stepUpRequired } from '../../test/fixtures';
import {
  closing,
  DAY_1,
  DAY_2,
  financeAdmin,
  financeServer,
  mzn,
  reconciliationLine,
} from '../../test/finance-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const FECHOS = `/finance?storeId=${baixa.id}&tab=fechos`;
const CLOSING = 'cccc0000-0000-4000-8000-000000000001';
const money = (amount: string) => formatMoney(mzn(amount)).replace(/\s/g, ' ');

type Sent = { path: string; key: string | null; body: unknown; headers: Headers };

/** Records every POST of the cash commands and answers with `answer` (one per call, the last repeats). */
function cashCommands(...answers: ((request: Request) => Response | Promise<Response>)[]) {
  const sent: Sent[] = [];
  server.use(
    http.post(api('/api/v1/backoffice/cash/*'), async ({ request }) => {
      const text = await request.text();
      sent.push({
        path: new URL(request.url).pathname,
        key: request.headers.get('Idempotency-Key'),
        body: text ? JSON.parse(text) : null,
        headers: request.headers,
      });
      const answer = answers[Math.min(sent.length - 1, answers.length - 1)]!;
      return answer(request);
    }),
  );
  return sent;
}

beforeEach(() => sessionStorage.clear());
afterEach(() => sessionStorage.clear());

describe('closings', () => {
  it("lists the store's closings waiting for approval, per method, with the server's differences", async () => {
    const { requests } = financeServer({ closings: [closing(CLOSING)] });

    renderApp(FECHOS);

    const table = await screen.findByRole('table', { name: 'Fechos de caixa' });
    const row = await within(table).findByRole('row', { name: /Caixa principal/ });
    expect(row).toHaveTextContent('Submetido');
    const lines = await screen.findByRole('table', { name: 'Fecho Caixa principal, ronda 1' });
    expect(within(lines).getByRole('row', { name: /CARD/ })).toHaveTextContent(money('-40.80'));
    expect(within(lines).getByRole('row', { name: /CARD/ })).toHaveTextContent('Talão em falta');
    expect(requests).toContain(`/api/v1/backoffice/cash/closings?storeId=${baixa.id}&status=SUBMITTED&page=0&size=20`);
  });

  it('approves a closing under one Idempotency-Key and reads the list again', async () => {
    const { data, requests } = financeServer({ closings: [closing(CLOSING)] });
    const sent = cashCommands(() => {
      data.closings = [];
      return HttpResponse.json(closing(CLOSING, 'APPROVED'));
    });
    const { user } = renderApp(FECHOS);

    await user.click(await screen.findByRole('button', { name: 'Aprovar fecho Caixa principal' }));

    expect(await screen.findByText('Não há fechos por aprovar.')).toBeInTheDocument();
    expect(sent).toHaveLength(1);
    expect(sent[0]!.path).toBe(`/api/v1/backoffice/cash/closings/${CLOSING}/approve`);
    expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests.filter((url) => url.includes('/cash/closings?'))).toHaveLength(2);
  });

  it('resends the same approval under the same key when the answer was lost', async () => {
    financeServer({ closings: [closing(CLOSING)] });
    const sent = cashCommands(
      () => problem(503, undefined),
      () => HttpResponse.json(closing(CLOSING, 'APPROVED')),
    );
    const { user } = renderApp(FECHOS);

    await user.click(await screen.findByRole('button', { name: 'Aprovar fecho Caixa principal' }));
    expect(await screen.findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    await vi.waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]!.key).toBe(sent[0]!.key);
  });

  it('rejects a closing with the reason the user gives', async () => {
    financeServer({ closings: [closing(CLOSING)] });
    const sent = cashCommands(() => HttpResponse.json(closing(CLOSING, 'REJECTED')));
    const { user } = renderApp(FECHOS);

    await user.click(await screen.findByRole('button', { name: 'Rejeitar fecho Caixa principal' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rejeitar fecho' });
    await user.click(within(dialog).getByRole('button', { name: 'Rejeitar' }));
    expect(await within(dialog).findByText('Indique o motivo.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Motivo'), 'Recontar o cartão');
    await user.click(within(dialog).getByRole('button', { name: 'Rejeitar' }));

    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.path).toBe(`/api/v1/backoffice/cash/closings/${CLOSING}/reject`);
    expect(sent[0]!.body).toEqual({ reason: 'Recontar o cartão' });
    expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('asks for a step-up when approving needs one, keeps the command and comes back to this tab', async () => {
    financeServer({ closings: [closing(CLOSING)] });
    const sent = cashCommands(() => stepUpRequired());
    const { user, leave } = renderApp(FECHOS);

    await user.click(await screen.findByRole('button', { name: 'Aprovar fecho Caixa principal' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Confirme a sua identidade' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirmar identidade' }));

    const target = new URL(leave.mock.calls[0]![0] as string, 'http://localhost');
    expect(target.pathname).toBe('/bff/step-up');
    expect(target.searchParams.get('return')).toBe(FECHOS);
    const stored = JSON.parse(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')!);
    expect(stored).toMatchObject({
      path: `/api/v1/backoffice/cash/closings/${CLOSING}/approve`,
      idempotencyKey: sent[0]!.key,
      returnTo: FECHOS,
    });
  });

  it('continues the approval after the step-up under the same key, and forgets it once sent', async () => {
    financeServer({ closings: [closing(CLOSING)] });
    const sent = cashCommands(() => HttpResponse.json(closing(CLOSING, 'APPROVED')));
    const key = crypto.randomUUID();
    savePendingCommand({
      method: 'POST',
      path: `/api/v1/backoffice/cash/closings/${CLOSING}/approve`,
      body: null,
      idempotencyKey: key,
      returnTo: FECHOS,
      label: 'Caixa principal',
      tenantId: companyA.tenantId,
    });
    const { user } = renderApp(FECHOS);

    const resume = await screen.findByRole('region', { name: 'Operação por continuar' });
    expect(within(resume).getByText('Aprovar o fecho Caixa principal.')).toBeInTheDocument();
    await user.click(within(resume).getByRole('button', { name: 'Continuar' }));

    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.key).toBe(key);
    expect(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')).toBeNull();
    await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Operação por continuar' })).not.toBeInTheDocument());
  });

  it('hides approve and reject from a user without CASH_CLOSING_APPROVE', async () => {
    // The administrator fixture holds CASH_CLOSING_APPROVE (the Financeiro area accepts it); this user only reads.
    const viewer = admin.permissions.filter((p) => p !== 'PERM_CASH_CLOSING_APPROVE');
    financeServer({ closings: [closing(CLOSING)] }, { ...admin, permissions: [...viewer, 'PERM_REPORT_VIEW'] });
    renderApp(FECHOS);

    await screen.findByRole('table', { name: 'Fechos de caixa' });
    expect(screen.queryByRole('button', { name: /Aprovar fecho/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Rejeitar fecho/ })).not.toBeInTheDocument();
  });

  it('shows every closing when the user picks "Todos"', async () => {
    const { requests } = financeServer({ closings: [closing(CLOSING, 'APPROVED')] });
    const { user } = renderApp(FECHOS);

    await user.selectOptions(await screen.findByLabelText('Estado dos fechos'), '');

    await vi.waitFor(() =>
      expect(requests).toContain(`/api/v1/backoffice/cash/closings?storeId=${baixa.id}&page=0&size=20`),
    );
    expect(await screen.findByText('Aprovado')).toBeInTheDocument();
  });
});

describe('cash days', () => {
  const days = [
    reconciliationLine({ cashDayId: DAY_1, businessDate: '2026-11-19', dayStatus: 'OPERATIONALLY_CLOSED' }),
    reconciliationLine({ cashDayId: DAY_1, businessDate: '2026-11-19', dayStatus: 'OPERATIONALLY_CLOSED', methodCode: 'CARD' }),
    reconciliationLine({ cashDayId: DAY_2, businessDate: '2026-11-18', dayStatus: 'FINANCIALLY_CLOSED' }),
  ];

  it('lists each day of the period once, with its state', async () => {
    financeServer({ reconciliation: days });
    renderApp(FECHOS);

    const table = await screen.findByRole('table', { name: 'Dias de caixa' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).getByRole('row', { name: /19\/11\/2026/ })).toHaveTextContent('Fechado operacionalmente');
    expect(within(table).getByRole('row', { name: /18\/11\/2026/ })).toHaveTextContent('Fechado financeiramente');
  });

  it("closes a day financially and explains the server's refusal when a closing is not approved", async () => {
    financeServer({ reconciliation: days });
    const sent = cashCommands(() => problem(422, 'CLOSINGS_NOT_APPROVED'));
    const { user } = renderApp(FECHOS);

    await user.click(await screen.findByRole('button', { name: 'Fecho financeiro do dia 19/11/2026' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Fecho financeiro do dia 19/11/2026?' });
    await user.click(within(dialog).getByRole('button', { name: 'Fechar o dia' }));

    expect(
      await screen.findByText('Há caixas do dia sem o fecho aprovado. Aprove-os antes do fecho financeiro.'),
    ).toBeInTheDocument();
    expect(sent[0]!.path).toBe(`/api/v1/backoffice/cash/days/${DAY_1}/financial-close`);
    expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("reopens a closed day with a reason and an approver's code and PIN, and keeps the PIN nowhere", async () => {
    financeServer({ reconciliation: days });
    const sent = cashCommands(() => HttpResponse.json({ id: DAY_2, status: 'OPEN', businessDate: '2026-11-18' }));
    const { user } = renderApp(FECHOS);

    await user.click(await screen.findByRole('button', { name: 'Reabrir o dia 18/11/2026' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reabrir o dia 18/11/2026' });
    await user.type(within(dialog).getByLabelText('Motivo'), 'Correção de despesa');
    await user.type(within(dialog).getByLabelText('Código do aprovador'), 'E042');
    await user.type(within(dialog).getByLabelText('PIN do aprovador'), '4321');
    await user.click(within(dialog).getByRole('button', { name: 'Reabrir' }));

    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.path).toBe(`/api/v1/backoffice/cash/days/${DAY_2}/reopen`);
    expect(sent[0]!.body).toEqual({ reason: 'Correção de despesa' });
    expect(sent[0]!.headers.get('X-Approver-Code')).toBe('E042');
    expect(sent[0]!.headers.get('X-Approver-Pin')).toBe('4321');
    expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(JSON.stringify({ ...sessionStorage, ...localStorage })).not.toContain('4321');
  });

  it('says when the period has no cash days', async () => {
    financeServer({ reconciliation: [] }, financeAdmin);
    renderApp(FECHOS);
    expect(await screen.findByText('Sem dias de caixa neste período.')).toBeInTheDocument();
  });
});
