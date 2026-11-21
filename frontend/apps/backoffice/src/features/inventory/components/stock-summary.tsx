import { Card } from '@restaurantpro/ui';
import { TriangleAlert, Warehouse, type LucideIcon } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { useBelowMinimum, useValuation } from '../hooks/use-inventory-reads';
import { Amount } from './amount';

/** The Stitch header cards: the store's stock value and its items below minimum, both as the server reports them. */
export function StockSummary({ storeId, currency }: { storeId: string; currency: string | undefined }) {
  const valuation = useValuation(storeId);
  const low = useBelowMinimum(storeId);
  const lowCount = low.data?.rows?.length;
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Kpi label="Valor total em stock" icon={Warehouse}>
        <Amount value={valuation.data?.totalValue} currency={currency} />
      </Kpi>
      <Kpi label="Abaixo do mínimo" icon={TriangleAlert}>
        {lowCount === undefined ? '—' : `${lowCount} ${lowCount === 1 ? 'artigo' : 'artigos'}`}
      </Kpi>
    </div>
  );
}

function Kpi({ label, icon: Icon, children }: { label: string; icon: LucideIcon; children: ReactNode }) {
  const id = useId();
  return (
    <Card role="group" aria-labelledby={id} className="flex-row items-start justify-between gap-2 px-6">
      <div className="flex flex-col gap-2">
        <span id={id} className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          {label}
        </span>
        <span className="text-2xl font-semibold">{children}</span>
      </div>
      <Icon aria-hidden="true" className="size-6 text-primary" />
    </Card>
  );
}
