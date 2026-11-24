import { Badge, Card, CardContent, CardHeader, CardTitle, cn, StatusBadge, TONE_TINT, type StatusMap } from '@restaurantpro/ui';
import { CircleCheck, CircleOff, Store } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { cutoffLabel } from '../store-values';
import type { StoreSummary } from '../types';
import { Facts } from './facts';

const states: StatusMap<'active' | 'inactive'> = {
  active: { label: 'Ativa', icon: CircleCheck, tone: 'success' },
  inactive: { label: 'Inativa', icon: CircleOff, tone: 'neutral' },
};

/** One store (Stitch "Unidades Operacionais & Lojas"): code, name, state and its settings; actions in `children`. */
export function StoreCard({ store, children }: { store: StoreSummary; children?: ReactNode }) {
  const titleId = useId();
  return (
    <Card role="article" aria-labelledby={titleId} className="gap-4">
      <CardHeader className="flex flex-row items-start gap-3">
        <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-xl', TONE_TINT.success)}>
          <Store aria-hidden="true" className="size-5" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <Badge variant="secondary" className="w-fit font-mono">
            {store.code}
          </Badge>
          <CardTitle id={titleId} className="text-lg">
            {store.name}
          </CardTitle>
        </div>
        <StatusBadge look="tint" status={store.active ? 'active' : 'inactive'} statuses={states} />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Facts
          className="sm:grid-cols-3 lg:grid-cols-2 xl:grid-cols-3"
          facts={[
            ['Fuso horário', store.timezone],
            ['Fecho do dia', cutoffLabel(store.businessDayCutoff)],
            ['Moeda', store.currency],
          ]}
        />
        {children}
      </CardContent>
    </Card>
  );
}
