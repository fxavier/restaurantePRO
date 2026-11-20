import { Money } from '@restaurantpro/ui';
import { Receipt } from 'lucide-react';
import { useSalesByDay } from '../hooks/use-reports';
import type { Period } from '../period';
import { KpiCard } from './kpi-card';

/** "Resumo": the period's figures as the reports compute them (§5); nothing is summed here. */
export function SummaryTab({ storeId, period }: { storeId: string; period: Period }) {
  const sales = useSalesByDay(storeId, period);
  const totals = sales.data?.totals;
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <KpiCard
        label="Faturação bruta"
        icon={Receipt}
        value={totals?.gross ? <Money value={totals.gross} /> : '—'}
        meta={totals ? `${totals.documents ?? 0} documentos` : undefined}
      />
    </div>
  );
}
