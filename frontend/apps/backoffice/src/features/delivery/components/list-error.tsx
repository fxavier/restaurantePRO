import { ApiError } from '@restaurantpro/api-client';
import { DataState } from '@restaurantpro/ui';
import { Lock } from 'lucide-react';
import { isUnavailable } from '../../../lib/errors';

type ListErrorProps = { error: unknown; forbidden: string; onRetry: () => void };

/** A list's own failure (FC §9): no permission in this store, no connection (with a retry), anything else. */
export function ListError({ error, forbidden, onRetry }: ListErrorProps) {
  if (error instanceof ApiError && error.status === 403) {
    return (
      <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
        <Lock aria-hidden="true" className="size-8 text-muted-foreground" />
        <p>{forbidden}</p>
      </div>
    );
  }
  return (
    <div className="mt-6">
      <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
        {null}
      </DataState>
    </div>
  );
}
