import { ApiError } from '@restaurantpro/api-client';
import { Card, DataState } from '@restaurantpro/ui';
import { Lock } from 'lucide-react';
import { isUnavailable } from '../../../lib/errors';

type ListErrorProps = { error: unknown; forbidden: string; onRetry: () => void };

/** A list's own failure (FC §9): no permission in this store, no connection (with a retry), anything else. */
export function ListError({ error, forbidden, onRetry }: ListErrorProps) {
  if (error instanceof ApiError && error.status === 403) {
    return (
      <Card className="mt-6 items-center gap-3 p-8 text-center">
        <Lock aria-hidden="true" className="size-8 text-muted-foreground" />
        <p>{forbidden}</p>
      </Card>
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
