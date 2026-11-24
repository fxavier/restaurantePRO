import { Button, Card, Skeleton, StatBox } from '@restaurantpro/ui';
import { TriangleAlert, Warehouse, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
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
      <Kpi label="Valor total em stock" icon={Warehouse} read={valuation} tone="muted">
        <Amount value={valuation.data?.totalValue} currency={currency} />
      </Kpi>
      <Kpi label="Abaixo do mínimo" icon={TriangleAlert} read={low} tone={lowCount > 0 ? 'warning' : 'muted'}>
        {`${lowCount} ${lowCount === 1 ? 'artigo' : 'artigos'}`}
      </Kpi>
    </Card>
  );
}

type KpiProps = { label: string; icon: LucideIcon; read: Read; tone: 'warning' | 'muted'; children: ReactNode };

/** One box (the shared StatBox): a skeleton while its read is pending, a compact error or "Sem ligação" with retry, else the value. */
function Kpi({ label, icon, read, tone, children }: KpiProps) {
  return (
    <StatBox
      caption={label}
      icon={icon}
      tone={tone}
      value={
        <KpiValue label={label} read={read}>
          {children}
        </KpiValue>
      }
    />
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
  return children;
}
