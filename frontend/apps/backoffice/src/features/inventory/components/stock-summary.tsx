import { Button, Card, cn, Skeleton, TONE_TINT } from '@restaurantpro/ui';
import { TriangleAlert, Warehouse, type LucideIcon } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { isUnavailable } from '../../../lib/errors';
import { useBelowMinimum, useValuation } from '../hooks/use-inventory-reads';
import { Amount } from './amount';

type Read = { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown };

/**
 * The store's stock value and its items below minimum, both as the server reports them: tinted boxes in one card, as
 * the POS payment summary (DESIGN.md "Light reference"). Items below minimum take the amber of "a confirmar" when
 * there are any; the count is also said in words.
 */
export function StockSummary({ storeId, currency }: { storeId: string; currency: string | undefined }) {
  const valuation = useValuation(storeId);
  const low = useBelowMinimum(storeId);
  const lowCount = low.data?.rows?.length ?? 0;
  return (
    <Card className="grid gap-3 p-4 sm:grid-cols-2">
      <Kpi label="Valor total em stock" icon={Warehouse} read={valuation} tint="bg-muted">
        <Amount value={valuation.data?.totalValue} currency={currency} />
      </Kpi>
      <Kpi label="Abaixo do mínimo" icon={TriangleAlert} read={low} tint={lowCount > 0 ? TONE_TINT.warning : 'bg-muted'}>
        {`${lowCount} ${lowCount === 1 ? 'artigo' : 'artigos'}`}
      </Kpi>
    </Card>
  );
}

type KpiProps = { label: string; icon: LucideIcon; read: Read; tint: string; children: ReactNode };

/** One box: a skeleton while its read is pending, a compact error or "Sem ligação" with retry, else the value. */
function Kpi({ label, icon: Icon, read, tint, children }: KpiProps) {
  const id = useId();
  return (
    <div role="group" aria-labelledby={id} className={cn('flex items-start justify-between gap-2 rounded-xl px-4 py-3', tint)}>
      <div className="flex min-h-16 flex-col gap-2">
        <span id={id} className="text-xs font-semibold tracking-wide uppercase">
          {label}
        </span>
        <KpiValue label={label} read={read}>
          {children}
        </KpiValue>
      </div>
      <Icon aria-hidden="true" className="size-6" />
    </div>
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
        {/* On white: the skeleton's own grey is the box's. */}
        <Skeleton className="h-8 w-32 bg-card" />
      </div>
    );
  }
  return <span className="text-2xl font-semibold tabular-nums">{children}</span>;
}
