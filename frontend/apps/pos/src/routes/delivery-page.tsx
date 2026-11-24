import { Button, Skeleton } from '@restaurantpro/ui';
import { CirclePlus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { DeliveryBoard } from '../features/delivery/components/delivery-board';
import { NewOrderDialog } from '../features/delivery/components/new-order-dialog';
import { useDeliveries } from '../features/delivery/hooks/use-deliveries';
import { loadFailure } from '../features/delivery/messages';
import { StatusBar } from './status-bar';

/** `/delivery` (§4; light reference, as on `/tables`): new takeaway and delivery orders, and the dispatch board. */
export function DeliveryPage() {
  const deliveries = useDeliveries();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-1">
            <h2 className="text-2xl font-semibold">Takeaway & Delivery</h2>
            <p className="text-muted-foreground">Pedidos para levar e entregas ao domicílio, do registo à expedição.</p>
          </div>
          <Button size="touch-primary" onClick={() => setCreating(true)}>
            <CirclePlus aria-hidden="true" />
            Novo pedido
          </Button>
        </header>
        {deliveries.data ? (
          deliveries.data.length === 0 ? (
            <p className="rounded-2xl border bg-card p-6 text-center text-muted-foreground">Nenhuma entrega em curso.</p>
          ) : (
            <DeliveryBoard deliveries={deliveries.data} />
          )
        ) : deliveries.isError ? (
          <div className="flex flex-col items-center gap-3 rounded-2xl border bg-card p-6 text-center">
            <p role="alert">{loadFailure(deliveries.error, 'as entregas')}</p>
            <Button variant="outline" size="touch" onClick={() => void deliveries.refetch()}>
              Tentar novamente
            </Button>
          </div>
        ) : (
          <div role="status" aria-label="A carregar as entregas…" className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            {Array.from({ length: 3 }, (_, index) => (
              <Skeleton key={index} className="h-64 rounded-2xl" />
            ))}
          </div>
        )}
      </main>
      {creating && <NewOrderDialog onClose={() => setCreating(false)} onOpened={(orderId) => navigate(`/orders/${orderId}`)} />}
    </div>
  );
}
