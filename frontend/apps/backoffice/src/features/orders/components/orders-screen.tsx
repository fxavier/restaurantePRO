import { Card, CardContent } from '@restaurantpro/ui';
import { useState } from 'react';
import { useStoreTimeZone } from '../../organization';
import { rangeError } from '../filters';
import { useOrderSelection } from '../hooks/use-order-selection';
import { useOrders, type OrderFilter } from '../hooks/use-orders';
import { OrderDetail } from './order-detail';
import { OrderFilters } from './order-filters';
import { OrdersCard } from './orders-card';

/** The store's orders (§5 "Pedidos"): the search on the left, the open order on the right. */
export function OrdersScreen({ storeId }: { storeId: string }) {
  const timeZone = useStoreTimeZone(storeId);
  const { orderId, selectOrder } = useOrderSelection();
  const [filter, setFilter] = useState<OrderFilter>({});
  const [page, setPage] = useState(0);
  const invalid = rangeError(filter);
  const orders = useOrders(storeId, filter, page, !invalid);

  return (
    <div className="mt-6 grid grid-cols-1 items-start gap-6 xl:grid-cols-5">
      <div className="flex flex-col gap-4 xl:col-span-3">
        <Card className="gap-4">
          <CardContent>
            <OrderFilters
              filter={filter}
              onChange={(next) => {
                setFilter(next);
                setPage(0);
              }}
              error={invalid}
            />
          </CardContent>
        </Card>
        <OrdersCard orders={orders} page={page} onPage={setPage} timeZone={timeZone} selectedId={orderId} onSelect={selectOrder} />
      </div>
      {orderId && (
        <div className="xl:col-span-2">
          <OrderDetail key={orderId} storeId={storeId} orderId={orderId} timeZone={timeZone} onClose={() => selectOrder(undefined)} />
        </div>
      )}
    </div>
  );
}
