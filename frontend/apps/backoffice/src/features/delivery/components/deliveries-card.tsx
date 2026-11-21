import { Button, Card, CardContent, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ComponentProps } from 'react';
import { DELIVERY_PAGE_SIZE, type DeliveryPage } from '../hooks/use-deliveries';
import { DeliveriesTable } from './deliveries-table';
import { ListError } from './list-error';

type DeliveriesCardProps = {
  deliveries: UseQueryResult<DeliveryPage>;
  page: number;
  onPage: (page: number) => void;
} & Omit<ComponentProps<typeof DeliveriesTable>, 'deliveries'>;

/** The period's deliveries, a page at a time, with the list's loading, empty and failure states. */
export function DeliveriesCard({ deliveries, page, onPage, ...table }: DeliveriesCardProps) {
  const data = deliveries.data;
  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Entregas do período</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {deliveries.isError ? (
          <ListError
            error={deliveries.error}
            forbidden="Sem permissão para ver as entregas desta loja."
            onRetry={() => void deliveries.refetch()}
          />
        ) : (
          <DataState
            state={deliveries.isPending ? 'loading' : data?.deliveries.length ? 'ready' : 'empty'}
            emptyMessage="Não há entregas neste período."
          >
            <DeliveriesTable deliveries={data?.deliveries ?? []} {...table} />
            <Pages page={page} pages={Math.ceil((data?.totalElements ?? 0) / DELIVERY_PAGE_SIZE)} onPage={onPage} />
          </DataState>
        )}
      </CardContent>
    </Card>
  );
}

function Pages({ page, pages, onPage }: { page: number; pages: number; onPage: (page: number) => void }) {
  if (pages <= 1) return null;
  return (
    <nav aria-label="Páginas" className="flex items-center justify-end gap-2 text-sm">
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
