import { formatMoney } from '@restaurantpro/ui';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, baixa, problem, stepUpRequired } from '../../test/fixtures';
import { financeServer, mzn, page, reconciliationLine, type AuditEventView, type DocumentSummary, type InDoubtAttemptView, type PaymentMethodView, type SeriesView } from '../../test/finance-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const tab = (name: string) => `/finance?storeId=${baixa.id}&tab=${name}`;
const money = (amount: string) => formatMoney(mzn(amount)).replace(/\s/g, ' ');
const NOW = new Date('2026-11-19T22:30:00Z');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  sessionStorage.clear();
});
afterEach(() => vi.useRealTimers());

/** Records the body of every write to `path` and answers with `answer`. */
function writes(method: 'post' | 'put', path: string, answer: () => Response) {
  const sent: { path: string; body: unknown }[] = [];
  server.use(
    http[method](api(path), async ({ request }) => {
      const text = await request.text();
      sent.push({ path: new URL(request.url).pathname, body: text ? JSON.parse(text) : null });
      return answer();
    }),
  );
  return sent;
}

/**
 * Two pages over the last seven days (`first` then `second`), one page over "Hoje" (`first` alone): a page past the
 * period's last page answers nothing, as the server would.
 */
function pagedByPeriod<Row>(request: Request, first: Row, second: Row) {
  const query = new URL(request.url).searchParams;
  const pageNumber = Number(query.get('page'));
  const today = query.get('from') === '2026-11-20T00:00:00+02:00';
  const total = today ? 1 : 21;
  const content = pageNumber === 0 ? [first] : pageNumber === 1 && !today ? [second] : [];
  return HttpResponse.json(page(content, pageNumber, 20, total));
}

describe('reconciliation', () => {
  it("shows expected against counted per day, register and method, as the server computed them", async () => {
    const { requests } = financeServer({
      reconciliation: [reconciliationLine({ methodCode: 'CARD', expected: mzn('1240.80'), counted: mzn('1200.00'), difference: mzn('-40.80') })],
    });
    renderApp(tab('reconciliacao'));

    const table = await screen.findByRole('table', { name: 'Reconciliação de caixa' });
    const row = await within(table).findByRole('row', { name: /CARD.*Caixa principal|Caixa principal.*CARD/ });
    expect(row).toHaveTextContent('19/11/2026');
    expect(row).toHaveTextContent(money('-40.80'));
    expect(requests).toContain(`/api/v1/backoffice/cash/reconciliation?storeId=${baixa.id}&from=2026-11-14&to=2026-11-20`);
  });

  it('shows a payment of unknown outcome as pending reconciliation, never as failed', async () => {
    const attempt: InDoubtAttemptView = {
      attemptId: 'abab0000-0000-4000-8000-000000000001',
      methodCode: 'MPESA',
      amount: mzn('350.00'),
      status: 'UNKNOWN',
      createdAt: '2026-11-19T18:05:00Z',
      pollCount: 3,
    };
    financeServer({ inDoubt: [attempt] });
    renderApp(tab('reconciliacao'));

    const table = await screen.findByRole('table', { name: 'Pagamentos por reconciliar' });
    const row = within(table).getByRole('row', { name: /MPESA/ });
    expect(row).toHaveTextContent('Por reconciliar');
    expect(row).toHaveTextContent(money('350.00'));
    expect(row).toHaveTextContent('19/11/26, 20:05');
    expect(row).not.toHaveTextContent(/falh/i);
  });
});

describe('fiscal', () => {
  const series: SeriesView = { id: 'f1f10000-0000-4000-8000-000000000001', storeId: baixa.id, code: 'FR2026', documentType: 'SALE', firstNumber: 1, nextNumber: 129, active: true, version: 0 };
  const document: DocumentSummary = {
    id: 'd0c00000-0000-4000-8000-000000000001',
    storeId: baixa.id,
    documentType: 'SALE',
    seriesCode: 'FR2026',
    number: 128,
    status: 'ISSUED',
    issuedAt: '2026-11-19T12:40:00Z',
    buyerName: 'Consumidor final',
    totalGross: mzn('845.00'),
  };

  it("lists the store's series and the documents of the period", async () => {
    const { requests } = financeServer({ series: [series], documents: [document] });
    renderApp(tab('fiscal'));

    const seriesTable = await screen.findByRole('table', { name: 'Séries de documentos' });
    expect(within(seriesTable).getByRole('row', { name: /FR2026/ })).toHaveTextContent('129');
    expect(within(seriesTable).getByRole('row', { name: /FR2026/ })).toHaveTextContent('Ativa');
    const documents = await screen.findByRole('table', { name: 'Documentos fiscais' });
    const row = within(documents).getByRole('row', { name: /FR2026\/128/ });
    expect(row).toHaveTextContent(money('845.00'));
    expect(row).toHaveTextContent('Emitido');
    expect(requests).toContain(
      `/api/v1/backoffice/fiscal/documents?storeId=${baixa.id}&from=2026-11-14T00%3A00%3A00%2B02%3A00&to=2026-11-21T00%3A00%3A00%2B02%3A00&page=0&size=20`,
    );
  });

  it('goes back to the first page of documents when the period changes', async () => {
    financeServer({ series: [series] });
    const second = { ...document, id: 'd0c00000-0000-4000-8000-000000000002', number: 107 };
    server.use(http.get(api('/api/v1/backoffice/fiscal/documents'), ({ request }) => pagedByPeriod(request, document, second)));
    const { user } = renderApp(tab('fiscal'));

    const table = await screen.findByRole('table', { name: 'Documentos fiscais' });
    await user.click(await screen.findByRole('button', { name: 'Seguinte' }));
    expect(await within(table).findByRole('row', { name: /FR2026\/107/ })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Período'), '1');

    expect(await within(await screen.findByRole('table', { name: 'Documentos fiscais' })).findByRole('row', { name: /FR2026\/128/ })).toBeInTheDocument();
  });

  it('asks for a step-up to read the series, and comes back to this tab', async () => {
    financeServer();
    server.use(http.get(api('/api/v1/backoffice/fiscal/series'), () => stepUpRequired()));
    const { user, leave } = renderApp(tab('fiscal'));

    expect(await screen.findByText('Para ver as séries fiscais, confirme a sua identidade.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirmar identidade' }));

    const target = new URL(leave.mock.calls[0]![0] as string, 'http://localhost');
    expect(target.searchParams.get('return')).toBe(tab('fiscal'));
  });

  it('creates a series for the store', async () => {
    financeServer();
    const sent = writes('post', '/api/v1/backoffice/fiscal/series', () => HttpResponse.json(series, { status: 201 }));
    const { user } = renderApp(tab('fiscal'));

    await user.click(await screen.findByRole('button', { name: 'Nova série' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova série' });
    await user.type(within(dialog).getByLabelText('Código'), 'FR2027');
    await user.type(within(dialog).getByLabelText('Primeiro número'), '1');
    await user.click(within(dialog).getByRole('button', { name: 'Criar série' }));

    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body).toEqual({ storeId: baixa.id, code: 'FR2027', firstNumber: 1 });
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it("shows the server's refusal of a second active series in the dialog", async () => {
    financeServer({ series: [series] });
    writes('post', '/api/v1/backoffice/fiscal/series', () => problem(409, 'ACTIVE_SERIES_EXISTS'));
    const { user } = renderApp(tab('fiscal'));

    await user.click(await screen.findByRole('button', { name: 'Nova série' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova série' });
    await user.type(within(dialog).getByLabelText('Código'), 'FR2027');
    await user.click(within(dialog).getByRole('button', { name: 'Criar série' }));

    expect(await within(dialog).findByText('A loja já tem uma série de venda ativa. Desative-a primeiro.')).toBeInTheDocument();
  });

  it("does not call a retried series a conflict, as the lost first answer may have created it", async () => {
    financeServer();
    const answers = [() => problem(503, undefined), () => problem(409, 'ACTIVE_SERIES_EXISTS')];
    const sent = writes('post', '/api/v1/backoffice/fiscal/series', () => answers[sent.length - 1]!());
    const { user } = renderApp(tab('fiscal'));

    await user.click(await screen.findByRole('button', { name: 'Nova série' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova série' });
    await user.type(within(dialog).getByLabelText('Código'), 'FR2027');
    await user.click(within(dialog).getByRole('button', { name: 'Criar série' }));
    await user.click(await within(dialog).findByRole('button', { name: 'Tentar novamente' }));

    expect(await within(dialog).findByText('O primeiro envio pode já ter sido registado. Confira a lista antes de criar outro.')).toBeInTheDocument();
    expect(within(dialog).queryByText('A loja já tem uma série de venda ativa. Desative-a primeiro.')).not.toBeInTheDocument();
  });

  it('deactivates a series after confirmation', async () => {
    financeServer({ series: [series] });
    const sent = writes('post', `/api/v1/backoffice/fiscal/series/${series.id}/deactivate`, () =>
      HttpResponse.json({ ...series, active: false }),
    );
    const { user } = renderApp(tab('fiscal'));

    await user.click(await screen.findByRole('button', { name: 'Desativar a série FR2026' }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Desativar' }));

    await vi.waitFor(() => expect(sent).toHaveLength(1));
  });
});

describe('payment methods', () => {
  const cash: PaymentMethodView = { id: 'aaaa1111-0000-4000-8000-000000000001', code: 'CASH', name: 'Numerário', kind: 'CASH', active: true, version: 2 };

  it("lists the company's payment methods with their kind and state", async () => {
    financeServer({ methods: [cash, { ...cash, id: 'aaaa1111-0000-4000-8000-000000000002', code: 'TPA', name: 'Cartão (TPA)', kind: 'EXTERNAL_TERMINAL', active: false }] });
    renderApp(tab('meios'));

    const table = await screen.findByRole('table', { name: 'Meios de pagamento' });
    expect(within(table).getByRole('row', { name: /Numerário/ })).toHaveTextContent('Dinheiro');
    expect(within(table).getByRole('row', { name: /Cartão \(TPA\)/ })).toHaveTextContent('Terminal externo');
    expect(within(table).getByRole('row', { name: /Cartão \(TPA\)/ })).toHaveTextContent('Inativo');
  });

  it('creates a payment method and shows a taken code on its field', async () => {
    financeServer();
    const sent = writes('post', '/api/v1/backoffice/payment-methods', () => problem(409, 'PAYMENT_METHOD_CODE_TAKEN'));
    const { user } = renderApp(tab('meios'));

    await user.click(await screen.findByRole('button', { name: 'Novo meio de pagamento' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo meio de pagamento' });
    await user.type(within(dialog).getByLabelText('Código'), 'MPESA');
    await user.type(within(dialog).getByLabelText('Nome'), 'M-Pesa');
    await user.selectOptions(within(dialog).getByLabelText('Tipo'), 'EXTERNAL_TERMINAL');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByText('Já existe um meio de pagamento com este código.')).toBeInTheDocument();
    expect(sent[0]!.body).toEqual({ code: 'MPESA', name: 'M-Pesa', kind: 'EXTERNAL_TERMINAL' });
  });

  it("does not call a retried create's own code taken, as the lost first answer may have created it", async () => {
    financeServer();
    const answers = [() => problem(503, undefined), () => problem(409, 'PAYMENT_METHOD_CODE_TAKEN')];
    const sent = writes('post', '/api/v1/backoffice/payment-methods', () => answers[sent.length - 1]!());
    const { user } = renderApp(tab('meios'));

    await user.click(await screen.findByRole('button', { name: 'Novo meio de pagamento' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo meio de pagamento' });
    await user.type(within(dialog).getByLabelText('Código'), 'MPESA');
    await user.type(within(dialog).getByLabelText('Nome'), 'M-Pesa');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
    await user.click(await within(dialog).findByRole('button', { name: 'Tentar novamente' }));

    expect(await within(dialog).findByText('O primeiro envio pode já ter sido registado. Confira a lista antes de criar outro.')).toBeInTheDocument();
    expect(within(dialog).queryByText('Já existe um meio de pagamento com este código.')).not.toBeInTheDocument();
    expect(sent).toHaveLength(2);
  });

  it('edits a payment method with the version it read', async () => {
    financeServer({ methods: [cash] });
    const sent = writes('put', `/api/v1/backoffice/payment-methods/${cash.id}`, () => HttpResponse.json({ ...cash, active: false, version: 3 }));
    const { user } = renderApp(tab('meios'));

    await user.click(await screen.findByRole('button', { name: 'Editar Numerário' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar meio de pagamento' });
    await user.click(within(dialog).getByLabelText('Ativo'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await vi.waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body).toEqual({ name: 'Numerário', active: false, version: 2 });
  });
});

describe('audit', () => {
  const event: AuditEventView = {
    id: 'a0d10000-0000-4000-8000-000000000001',
    occurredAt: '2026-11-19T12:15:22Z',
    action: 'CASH_DAY_REOPENED',
    entityType: 'CASH_DAY',
    entityId: 'dddd0000-0000-4000-8000-000000000001',
    actorKind: 'ACCOUNT',
    reason: 'Correção de despesa',
    storeId: baixa.id,
  };

  it("lists the store's audit events of the period in the store's time", async () => {
    const { requests } = financeServer({ events: [event] });
    renderApp(tab('auditoria'));

    const table = await screen.findByRole('table', { name: 'Eventos de auditoria' });
    const row = within(table).getByRole('row', { name: /CASH_DAY_REOPENED/ });
    expect(row).toHaveTextContent('19/11/26, 14:15');
    expect(row).toHaveTextContent('Correção de despesa');
    expect(requests).toContain(
      `/api/v1/backoffice/audit/events?storeId=${baixa.id}&from=2026-11-14T00%3A00%3A00%2B02%3A00&to=2026-11-21T00%3A00%3A00%2B02%3A00&page=0&size=20`,
    );
  });

  it('goes back to the first page when the period changes', async () => {
    financeServer();
    const second = { ...event, id: 'a0d10000-0000-4000-8000-000000000002', action: 'SERIES_DEACTIVATED' };
    server.use(http.get(api('/api/v1/backoffice/audit/events'), ({ request }) => pagedByPeriod(request, event, second)));
    const { user } = renderApp(tab('auditoria'));

    const table = await screen.findByRole('table', { name: 'Eventos de auditoria' });
    await user.click(await screen.findByRole('button', { name: 'Seguinte' }));
    expect(await within(table).findByRole('row', { name: /SERIES_DEACTIVATED/ })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Período'), '1');

    expect(await within(await screen.findByRole('table', { name: 'Eventos de auditoria' })).findByRole('row', { name: /CASH_DAY_REOPENED/ })).toBeInTheDocument();
  });

  it('says when nothing was recorded in the period', async () => {
    financeServer();
    renderApp(tab('auditoria'));
    expect(await screen.findByText('Sem eventos neste período.')).toBeInTheDocument();
  });
});
