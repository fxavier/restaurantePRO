import { Button, Card, Skeleton } from '@restaurantpro/ui';
import { TriangleAlert, Warehouse, type LucideIcon } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { isUnavailable } from '../../../lib/errors';
import { useBelowMinimum, useValuation } from '../hooks/use-inventory-reads';
import { Amount } from './amount';

type Read = { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown };

/** The Stitch header cards: the store's stock value and its items below minimum, both as the server reports them. */
export function StockSummary({ storeId, currency }: { storeId: string; currency: string | undefined }) {
  const valuation = useValuation(storeId);
  const low = useBelowMinimum(storeId);
  const lowCount = low.data?.rows?.length ?? 0;
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Kpi label="Valor total em stock" icon={Warehouse} read={valuation}>
        <Amount value={valuation.data?.totalValue} currency={currency} />
      </Kpi>
      <Kpi label="Abaixo do mínimo" icon={TriangleAlert} read={low}>
        {`${lowCount} ${lowCount === 1 ? 'artigo' : 'artigos'}`}
      </Kpi>
    </div>
  );
}

/** One card: a skeleton while its read is pending, a compact error or "Sem ligação" with retry, else the value. */
function Kpi({ label, icon: Icon, read, children }: { label: string; icon: LucideIcon; read: Read; children: ReactNode }) {
  const id = useId();
  return (
    <Card role="group" aria-labelledby={id} className="flex-row items-start justify-between gap-2 px-6">
      <div className="flex min-h-16 flex-col gap-2">
        <span id={id} className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          {label}
        </span>
        <KpiValue label={label} read={read}>
          {children}
        </KpiValue>
      </div>
      <Icon aria-hidden="true" className="size-6 text-primary" />
    </Card>
  );
}

function KpiValue({ label, read, children }: { label: string; read: Read; children: ReactNode }) {
  if (read.isError) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <span role="alert" className="text-sm">
          {isUnavailable(read.error) ? 'Sem ligação ao serviço.' : 'Não foi possível carregar.'}
        </span>
        <Button variant="outline" size="sm" aria-label={`Tentar novamente: ${label}`} onClick={() => void read.refetch()}>
          Tentar novamente
        </Button>
      </div>
    );
  }
  if (read.isPending) {
    return (
      <div role="status" aria-busy="true" aria-label="A carregar…">
        <Skeleton className="h-8 w-32" />
      </div>
    );
  }
  return <span className="text-2xl font-semibold">{children}</span>;
}
