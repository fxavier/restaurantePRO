import { Button, Card, CardContent, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ComponentProps } from 'react';
import { ORDERS_PAGE_SIZE, type OrdersPage } from '../hooks/use-orders';
import { OrdersTable } from './orders-table';
import { ReadFailure } from './read-failure';

type OrdersCardProps = {
  orders: UseQueryResult<OrdersPage>;
  page: number;
  onPage: (page: number) => void;
} & Omit<ComponentProps<typeof OrdersTable>, 'orders'>;

/** The orders found, a page at a time, with the count the server gives and the list's loading, empty and failure states. */
export function OrdersCard({ orders, page, onPage, ...table }: OrdersCardProps) {
  const data = orders.data;
  return (
    <Card className="gap-4">
      <CardHeader className="flex items-center justify-between gap-2">
        <CardTitle className="text-lg">Pedidos encontrados</CardTitle>
        {data && <span className="text-sm text-muted-foreground tabular-nums">{data.totalElements} pedidos</span>}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {orders.isError ? (
          <ReadFailure
            error={orders.error}
            forbidden="Sem permissão para ver os pedidos desta loja."
            onRetry={() => void orders.refetch()}
          />
        ) : (
          <DataState
            state={orders.isPending ? 'loading' : data?.orders.length ? 'ready' : 'empty'}
            emptyMessage="Nenhum pedido corresponde à pesquisa."
          >
            <OrdersTable orders={data?.orders ?? []} {...table} />
            <Pager page={page} pages={Math.ceil((data?.totalElements ?? 0) / ORDERS_PAGE_SIZE)} onPage={onPage} />
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

function Pager({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  if (pages <= 1) return null;
  return (
    <nav aria-label="Páginas de pedidos" className="flex items-center justify-end gap-2 text-sm">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Anterior
      </Button>
      <span>
        Página {page + 1} de {pages}
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Seguinte
      </Button>
    </nav>
  );
}
