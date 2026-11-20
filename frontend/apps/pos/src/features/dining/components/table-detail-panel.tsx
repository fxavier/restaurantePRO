import { Badge, Button, DataState, Money } from '@restaurantpro/ui';
import { ClipboardPlus, DoorOpen, X } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { ScrollRegion } from '../../../lib/scroll-region';
import { SentRounds } from '../../orders/components/order-panel';
import { useOrder } from '../../orders/hooks/use-order';
import { clockTime, guestsLabel, seatsLabel } from '../format';
import type { DiningTable } from '../table-map';
import { type TableState, tableState } from '../table-state';
import { indicators } from './table-tile';

/** The code block's fill: the state's colour, as on the tile (DESIGN.md "Table states"). */
const STATE_FILL: Record<TableState, string> = {
  free: 'bg-success text-success-foreground',
  occupied: 'bg-warning text-warning-foreground',
  bill: 'bg-info text-info-foreground',
  locked: 'bg-destructive text-destructive-foreground',
};

type Props = {
  /** The selected table; none yet before the first tap. */
  table: DiningTable | undefined;
  areaName: string | undefined;
  /** A free table: "Abrir mesa" (the open-table dialog). */
  onOpenTable: (table: DiningTable) => void;
  /** An occupied table: "Registar artigos" (its order). */
  onGoToOrder: (table: DiningTable) => void;
  /** Below `lg` the panel covers the bottom of the map; "Fechar" clears the selection. */
  onClose: () => void;
};

/**
 * The selected table's detail (Stitch's right panel): its state, guests and opening time, and an occupied table's
 * rounds and total, read with the order screen's query. A column from `lg`; a panel over the bottom of the map below.
 */
export function TableDetailPanel({ table, areaName, onOpenTable, onGoToOrder, onClose }: Props) {
  const heading = useRef<HTMLHeadingElement>(null);
  const timeZone = useStoreTimeZone();

  // Selecting a table takes keyboard and screen-reader users to its detail.
  useEffect(() => {
    if (table) heading.current?.focus();
  }, [table?.id]); // eslint-disable-line react-hooks/exhaustive-deps -- only a new selection moves focus, not a refresh

  const shell = `${table ? 'flex' : 'hidden lg:flex'} fixed inset-x-0 bottom-0 z-40 max-h-2/3 flex-col border-t bg-card lg:static lg:max-h-none lg:w-96 lg:shrink-0 lg:rounded-xl lg:border`;

  if (!table) {
    return (
      <aside aria-label="Detalhe da mesa" className={`${shell} items-center justify-center p-6`}>
        <p className="text-center text-muted-foreground">Toque numa mesa para ver o detalhe.</p>
      </aside>
    );
  }

  const occupied = table.status === 'OCCUPIED';
  const meta = [areaName, occupied && table.guests !== null ? guestsLabel(table.guests) : seatsLabel(table.seats)]
    .filter(Boolean)
    .join(' · ');

  return (
    <aside aria-label="Detalhe da mesa" className={shell}>
      <header className="flex items-start gap-3 border-b p-4">
        <span
          aria-hidden="true"
          className={`flex size-14 shrink-0 items-center justify-center rounded-lg text-2xl font-bold tabular-nums ${STATE_FILL[tableState(table)]}`}
        >
          {table.code}
        </span>
        <div className="min-w-0 flex-1">
          <h2 ref={heading} tabIndex={-1} className="truncate text-2xl font-semibold focus-visible:outline-none">
            Mesa {table.code}
          </h2>
          <p className="text-muted-foreground">{meta}</p>
        </div>
        <Button variant="ghost" size="touch" aria-label="Fechar" className="lg:hidden" onClick={onClose}>
          <X aria-hidden="true" />
        </Button>
      </header>
      <ScrollRegion label={`Detalhe da mesa ${table.code}`} className="flex min-h-0 flex-1 flex-col gap-4 p-4">
        <dl className="grid grid-cols-2 gap-3">
          <div>
            <dt className="text-sm text-muted-foreground">Estado</dt>
            <dd className="font-semibold">{occupied ? 'Ocupada' : 'Livre'}</dd>
          </div>
          {table.openedAt && (
            <div>
              <dt className="text-sm text-muted-foreground">Abertura</dt>
              <dd className="font-semibold tabular-nums">{clockTime(table.openedAt, timeZone)}</dd>
            </div>
          )}
        </dl>
        {indicators(table, timeZone).length > 0 && (
          <ul aria-label="Indicadores" className="flex flex-wrap gap-2">
            {indicators(table, timeZone).map((badge) => (
              <li key={badge.label}>
                <Badge variant={badge.variant} className="h-6 [&>svg]:size-3.5">
                  {badge.icon}
                  {badge.label}
                </Badge>
              </li>
            ))}
          </ul>
        )}
        {occupied && table.openOrderId && <OrderSummary orderId={table.openOrderId} />}
      </ScrollRegion>
      <footer className="border-t p-4">
        {occupied ? (
          <Button size="touch-primary" className="w-full" onClick={() => onGoToOrder(table)}>
            <ClipboardPlus aria-hidden="true" />
            Registar artigos
          </Button>
        ) : (
          <Button size="touch-primary" className="w-full" onClick={() => onOpenTable(table)}>
            <DoorOpen aria-hidden="true" />
            Abrir mesa
          </Button>
        )}
      </footer>
    </aside>
  );
}

/** The occupied table's rounds and the server's total; the drafts stay on the order screen. */
function OrderSummary({ orderId }: { orderId: string }) {
  const order = useOrder(orderId);
  return (
    <section aria-labelledby="table-rounds-title" className="flex flex-col gap-2">
      <h3 id="table-rounds-title" className="text-lg font-semibold">
        Rondas enviadas
      </h3>
      <DataState
        state={order.data ? 'ready' : order.isError ? 'error' : 'loading'}
        onRetry={() => void order.refetch()}
      >
        {order.data && (
          <>
            {order.data.rounds.length === 0 && order.data.unbatched.length === 0 ? (
              <p className="text-muted-foreground">Nada enviado ainda.</p>
            ) : (
              <SentRounds order={order.data} />
            )}
            <div role="group" aria-label="Total" className="mt-2 flex items-baseline justify-between gap-2 rounded-lg bg-muted p-3">
              <span className="text-lg font-semibold">Total</span>
              <Money value={order.data.due} className="text-4xl font-bold text-primary" />
            </div>
          </>
        )}
      </DataState>
    </section>
  );
}
