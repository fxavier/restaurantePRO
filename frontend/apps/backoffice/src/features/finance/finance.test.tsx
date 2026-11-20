import { formatMoney } from '@restaurantpro/ui';
import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { baixa } from '../../test/fixtures';
import { financeServer, mzn, salesRow } from '../../test/finance-fixtures';
import { renderApp } from '../../test/render-app';

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
});
