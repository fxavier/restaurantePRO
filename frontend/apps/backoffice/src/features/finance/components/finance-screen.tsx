import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { useFinanceTab, type FinanceTab } from '../hooks/use-finance-tab';
import { useStore } from '../hooks/use-store';
import { lastDays } from '../period';
import { AuditTab } from './audit-tab';
import { CashSessionsTab } from './cash-sessions-tab';
import { ClosingsTab } from './closings-tab';
import { FiscalTab } from './fiscal-tab';
import { PaymentMethodsTab } from './payment-methods-tab';
import { QueryState } from './query-state';
import { ReconciliationTab } from './reconciliation-tab';
import { ResumeFinanceCommand } from './resume-finance-command';
import { SummaryTab } from './summary-tab';
import { selectClass } from './styles';

const TAB_LABELS: Record<FinanceTab, string> = {
  resumo: 'Resumo',
  fechos: 'Fechos',
  sessoes: 'Sessões de caixa',
  reconciliacao: 'Reconciliação',
  fiscal: 'Fiscal',
  meios: 'Meios de pagamento',
  auditoria: 'Auditoria',
};

const PERIODS = [
  { days: 1, label: 'Hoje' },
  { days: 7, label: 'Últimos 7 dias' },
  { days: 30, label: 'Últimos 30 dias' },
];

/**
 * The selected store's finance area (§5). It needs the store's timezone, so it waits for the store list and explains
 * a list that fails or has no such store (DS principle 5) instead of leaving the area blank.
 */
export function FinanceScreen({ storeId }: { storeId: string }) {
  const { query, store } = useStore(storeId);
  return (
    <section aria-label="Financeiro da loja" className="mt-6 min-w-0">
      <QueryState
        query={query}
        empty={!store}
        emptyMessage="Loja não encontrada. Escolha outra na barra superior."
        subject="as lojas desta empresa"
      >
        {store && <FinanceArea storeId={storeId} timeZone={store.timezone} />}
      </QueryState>
    </section>
  );
}

/** The store's tabs over one period, in the store's timezone. */
function FinanceArea({ storeId, timeZone }: { storeId: string; timeZone: string }) {
  const { tab, selectTab } = useFinanceTab();
  const [days, setDays] = useState(7);
  const periodId = useId();
  const period = lastDays(days, new Date(), timeZone);
  // A paged list starts again at its first page when the period changes (a past page may not exist in the new one).
  const periodKey = `${period.from}/${period.to}`;
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <ResumeFinanceCommand storeId={storeId} />
      <Tabs value={tab} onValueChange={selectTab} className="gap-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <TabsList className="max-w-full justify-start overflow-x-auto">
            {Object.entries(TAB_LABELS).map(([value, label]) => (
              <TabsTrigger key={value} value={value} className="shrink-0">
                {label}
              </TabsTrigger>
            ))}
          </TabsList>
          <div className="flex flex-col gap-1">
            <label htmlFor={periodId} className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              Período
            </label>
            <select id={periodId} className={selectClass} value={days} onChange={(event) => setDays(Number(event.target.value))}>
              {PERIODS.map((option) => (
                <option key={option.days} value={option.days}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        </div>
        <TabsContent value="resumo">
          <SummaryTab storeId={storeId} period={period} />
        </TabsContent>
        <TabsContent value="fechos">
          <ClosingsTab storeId={storeId} period={period} />
        </TabsContent>
        <TabsContent value="sessoes">
          <CashSessionsTab storeId={storeId} period={period} periodKey={periodKey} />
        </TabsContent>
        <TabsContent value="reconciliacao">
          <ReconciliationTab storeId={storeId} period={period} />
        </TabsContent>
        <TabsContent value="fiscal">
          <FiscalTab storeId={storeId} period={period} periodKey={periodKey} />
        </TabsContent>
        <TabsContent value="meios">
          <PaymentMethodsTab storeId={storeId} />
        </TabsContent>
        <TabsContent value="auditoria">
          <AuditTab key={periodKey} storeId={storeId} period={period} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
