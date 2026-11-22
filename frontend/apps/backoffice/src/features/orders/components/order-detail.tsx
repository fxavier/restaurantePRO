import { Button, Card, CardContent, CardHeader, DataState, Money, StatusBadge } from '@restaurantpro/ui';
import { X } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { useOrder } from '../hooks/use-orders';
import { businessDate, storeTime } from '../format';
import { lifecycles, modes } from '../labels';
import type { OrderView } from '../types';
import { OrderAdjustments } from './order-adjustments';
import { OrderLines } from './order-lines';
import { OrderRounds } from './order-rounds';
import { ReadFailure } from './read-failure';

type OrderDetailProps = { orderId: string; timeZone: string | undefined; onClose: () => void };

/** The open order (GET /backoffice/orders/{id}), read only: its facts, what is due, lines, rounds and discounts. */
export function OrderDetail({ orderId, timeZone, onClose }: OrderDetailProps) {
  const titleId = useId();
  const order = useOrder(orderId);
  const data = order.data;
  return (
    <Card role="region" aria-labelledby={titleId} className="gap-4">
      <CardHeader className="flex items-center justify-between gap-2">
        <h2 id={titleId} className="text-lg font-semibold">
          {data ? `Pedido #${data.orderNumber}` : 'Pedido'}
        </h2>
        <Button variant="ghost" size="icon" aria-label="Fechar detalhe" onClick={onClose}>
          <X aria-hidden="true" />
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {order.isError ? (
          <ReadFailure
            error={order.error}
            forbidden="Sem permissão para ver este pedido."
            notFound="Este pedido não existe nesta empresa."
            onRetry={() => void order.refetch()}
          />
        ) : data ? (
          <OrderBody order={data} timeZone={timeZone} />
        ) : (
          <DataState state="loading">{null}</DataState>
        )}
      </CardContent>
    </Card>
  );
}

function OrderBody({ order, timeZone }: { order: OrderView; timeZone: string | undefined }) {
  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
          <Fact term="Estado">
            <StatusBadge status={order.lifecycle} statuses={lifecycles} />
          </Fact>
          <Fact term="Modo">{modes[order.mode]}</Fact>
          <Fact term="Mesa">{order.tableCode ?? '—'}</Fact>
          <Fact term="Pessoas">{order.guests ?? '—'}</Fact>
          <Fact term="Dia de negócio">{businessDate(order.businessDay)}</Fact>
          <Fact term="Aberto">{storeTime(order.openedAt, timeZone, true)}</Fact>
          <Fact term="Fechado">{storeTime(order.closedAt, timeZone, true)}</Fact>
        </dl>
        <div role="group" aria-label="A pagar" className="min-w-40 rounded-xl border bg-muted p-4">
          <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">A pagar</p>
          <Money value={order.due} className="text-2xl font-semibold text-primary" />
        </div>
      </div>
      <OrderLines lines={order.lines} batches={order.batches} />
      <OrderRounds batches={order.batches} timeZone={timeZone} />
      <OrderAdjustments lines={order.lines} timeZone={timeZone} />
    </>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{term}</dt>
      <dd className="tabular-nums">{children}</dd>
    </>
  );
}
