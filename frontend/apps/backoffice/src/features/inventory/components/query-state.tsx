import { ApiError } from '@restaurantpro/api-client';
import { Button, DataState, type DataStateValue } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { isUnavailable } from '../../../lib/errors';

type Query = { isError: boolean; error: unknown; refetch: () => unknown };

/** A read's loading, empty, error, no-permission and unavailable states (DS principle 5); the content when ready. */
export function QueryState({
  query,
  pending,
  empty,
  emptyMessage,
  children,
}: {
  query: Query;
  pending: boolean;
  empty: boolean;
  emptyMessage: string;
  children: ReactNode;
}) {
  if (query.isError && query.error instanceof ApiError && query.error.status === 403) {
    return <p className="py-6 text-center text-muted-foreground">Sem permissão para ver esta informação.</p>;
  }
  return (
    <DataState state={stateOf(query, pending, empty)} emptyMessage={emptyMessage} onRetry={() => void query.refetch()}>
      {children}
    </DataState>
  );
}

function stateOf(query: Query, pending: boolean, empty: boolean): DataStateValue {
  if (query.isError) return isUnavailable(query.error) ? 'unavailable' : 'error';
  if (pending) return 'loading';
  return empty ? 'empty' : 'ready';
}

/** Server-side pages (§9): Anterior / Seguinte, shown only when there is more than one. */
export function Pages({ page, total, size, onPage }: { page: number; total: number; size: number; onPage: (page: number) => void }) {
  const pages = Math.ceil(total / size);
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
