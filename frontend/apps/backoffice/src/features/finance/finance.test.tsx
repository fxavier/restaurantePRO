import { formatMoney } from '@restaurantpro/ui';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, baixa, problem } from '../../test/fixtures';
import { financeServer, mzn, page, receiptsRow, salesRow } from '../../test/finance-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const FINANCE = `/finance?storeId=${baixa.id}`;
/** 22:30 UTC on 19 November is already the 20th in Maputo (UTC+2): the store's day, not the browser's. */
const NOW = new Date('2026-11-19T22:30:00Z');

/** An MZN amount as the screen shows it; Intl's no-break spaces read as spaces, as Testing Library normalises text. */
const money = (amount: string) => formatMoney(mzn(amount)).replace(/\s/g, ' ');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

describe('finance summary', () => {
  it("shows the store's gross sales of the last seven days, from the server's totals", async () => {
    const { requests } = financeServer({ salesTotals: salesRow('total', 'Total', '2840.90', 68) });

    renderApp(FINANCE);

    expect(await screen.findByRole('heading', { level: 1, name: 'Financeiro, Fechos & Auditoria' })).toBeInTheDocument();
    const kpi = await screen.findByRole('group', { name: 'Faturação bruta' });
    expect(await within(kpi).findByText(money('2840.90'))).toBeInTheDocument();
    expect(within(kpi).getByText('68 documentos')).toBeInTheDocument();
    expect(requests).toContain(`/api/v1/backoffice/reports/sales?storeId=${baixa.id}&from=2026-11-14&to=2026-11-20&groupBy=day`);
  });

  it('shows net, tax and received totals and the per-day and per-method tables as the server computed them', async () => {
    const { requests } = financeServer({
      salesTotals: { ...salesRow('total', 'Total', '2840.90', 68), net: mzn('2449.05'), tax: mzn('391.85') },
      sales: [salesRow('2026-11-19', '2026-11-19', '1200.00', 30), salesRow('2026-11-20', '2026-11-20', '1640.90', 38)],
      receipts: [receiptsRow('CASH', 'CASH', '590.50'), receiptsRow('CARD', 'CARD', '1240.80')],
      receiptsTotals: receiptsRow('total', 'Total', '1831.30'),
    });

    renderApp(FINANCE);

    expect(await within(await screen.findByRole('group', { name: 'Faturação líquida' })).findByText(money('2449.05'))).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: 'Impostos' })).getByText(money('391.85'))).toBeInTheDocument();
    expect(await within(screen.getByRole('group', { name: 'Recebido' })).findByText(money('1831.30'))).toBeInTheDocument();
    const byDay = screen.getByRole('table', { name: 'Vendas por dia' });
    expect(within(byDay).getByRole('row', { name: /19\/11\/2026.*30/ })).toHaveTextContent(money('1200.00'));
    const byMethod = await screen.findByRole('table', { name: 'Recebimentos por meio de pagamento' });
    expect(within(byMethod).getByRole('row', { name: /CARD/ })).toHaveTextContent(money('1240.80'));
    expect(requests).toContain(`/api/v1/backoffice/reports/receipts?storeId=${baixa.id}&from=2026-11-14&to=2026-11-20&groupBy=method`);
  });

  it('reads another period when the user picks one', async () => {
    const { requests } = financeServer();
    const { user } = renderApp(FINANCE);

    await user.selectOptions(await screen.findByLabelText('Período'), '30');

    await vi.waitFor(() =>
      expect(requests).toContain(`/api/v1/backoffice/reports/sales?storeId=${baixa.id}&from=2026-10-22&to=2026-11-20&groupBy=day`),
    );
  });

  it('says the figures are provisional while the server has facts still to project', async () => {
    financeServer();
    server.use(
      http.get(api('/api/v1/backoffice/reports/sales'), () =>
        HttpResponse.json({ storeId: baixa.id, final: false, pendingFacts: 3, rows: [], totals: salesRow('total', 'Total', '0.00', 0) }),
      ),
    );

    renderApp(FINANCE);

    expect(await screen.findByText('Valores provisórios: 3 movimentos ainda por processar.')).toBeInTheDocument();
  });

  it('says when there were no sales in the period', async () => {
    financeServer();
    renderApp(FINANCE);
    expect(await screen.findByText('Sem vendas neste período.')).toBeInTheDocument();
  });

  it('explains a report the user may not see, and offers a retry when the service is unreachable', async () => {
    financeServer();
    server.use(http.get(api('/api/v1/backoffice/reports/sales'), () => problem(403, 'ACCESS_DENIED')));
    server.use(http.get(api('/api/v1/backoffice/reports/receipts'), () => problem(503, undefined)));

    renderApp(FINANCE);

    expect(await screen.findByText('Sem permissão para ver os relatórios desta loja.')).toBeInTheDocument();
    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument();
  });
});

describe('finance tabs', () => {
  it('keeps the open tab in the address, beside the store', async () => {
    financeServer();
    const { user, currentUrl } = renderApp(FINANCE);

    await user.click(await screen.findByRole('tab', { name: 'Auditoria' }));

    expect(currentUrl()).toBe(`/finance?storeId=${baixa.id}&tab=auditoria`);
    expect(screen.getByRole('tab', { name: 'Auditoria' })).toHaveAttribute('aria-selected', 'true');
    for (const name of ['Resumo', 'Fechos', 'Reconciliação', 'Fiscal', 'Meios de pagamento']) {
      expect(screen.getByRole('tab', { name })).toBeInTheDocument();
    }
  });

  it("offers a retry when the store list is unreachable, instead of a blank area", async () => {
    financeServer();
    let unreachable = true;
    server.use(
      http.get(api('/api/v1/backoffice/stores'), () =>
        unreachable ? problem(503, undefined) : HttpResponse.json(page([baixa], 0, 100)),
      ),
    );
    const { user } = renderApp(FINANCE);

    const area = await screen.findByRole('region', { name: 'Financeiro da loja' });
    expect(await within(area).findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    unreachable = false;
    await user.click(within(area).getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('tab', { name: 'Resumo' })).toBeInTheDocument();
  });

  it('says when the selected store is not in the company', async () => {
    financeServer();
    renderApp('/finance?storeId=99990000-0000-4000-8000-000000000099');
    expect(await screen.findByText('Loja não encontrada. Escolha outra na barra superior.')).toBeInTheDocument();
  });

  it('asks for a store first', async () => {
    financeServer();
    renderApp('/finance');
    expect(await screen.findByText('Escolha uma loja na barra superior para ver o seu financeiro.')).toBeInTheDocument();
  });
});
