import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { useFinanceTab, type FinanceTab } from '../hooks/use-finance-tab';
import { useStore } from '../hooks/use-store';
import { lastDays } from '../period';
import { AuditTab } from './audit-tab';
import { ClosingsTab } from './closings-tab';
import { FiscalTab } from './fiscal-tab';
import { PaymentMethodsTab } from './payment-methods-tab';
import { ReconciliationTab } from './reconciliation-tab';
import { ResumeFinanceCommand } from './resume-finance-command';
import { SummaryTab } from './summary-tab';
import { selectClass } from './styles';

const TAB_LABELS: Record<FinanceTab, string> = {
  resumo: 'Resumo',
  fechos: 'Fechos',
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

/** The selected store's finance area (§5): its tabs over one period, in the store's timezone. */
export function FinanceScreen({ storeId }: { storeId: string }) {
  const store = useStore(storeId);
  const { tab, selectTab } = useFinanceTab();
  const [days, setDays] = useState(7);
  const periodId = useId();
  if (!store) return null;
  const period = lastDays(days, new Date(), store.timezone);
  return (
    <div className="mt-6 flex flex-col gap-6">
      <ResumeFinanceCommand storeId={storeId} />
      <Tabs value={tab} onValueChange={selectTab} className="gap-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <TabsList className="flex-wrap">
            {Object.entries(TAB_LABELS).map(([value, label]) => (
              <TabsTrigger key={value} value={value}>
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
        <TabsContent value="reconciliacao">
          <ReconciliationTab storeId={storeId} period={period} />
        </TabsContent>
        <TabsContent value="fiscal">
          <FiscalTab storeId={storeId} period={period} />
        </TabsContent>
        <TabsContent value="meios">
          <PaymentMethodsTab storeId={storeId} />
        </TabsContent>
        <TabsContent value="auditoria">
          <AuditTab storeId={storeId} period={period} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
