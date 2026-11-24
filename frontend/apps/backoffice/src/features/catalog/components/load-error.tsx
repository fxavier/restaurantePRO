import { ApiError } from '@restaurantpro/api-client';
import { Card, DataState } from '@restaurantpro/ui';
import { Lock } from 'lucide-react';
import { isUnavailable } from '../../../lib/errors';

type LoadErrorProps = { error: unknown; onRetry: () => void; forbidden: string };

/** A list that could not be read: no permission (403, said in `forbidden`), no connection, or anything else. */
export function LoadError({ error, onRetry, forbidden }: LoadErrorProps) {
  if (error instanceof ApiError && error.status === 403) {
    return (
      <Card className="items-center gap-3 p-8 text-center">
        <Lock aria-hidden="true" className="size-8 text-muted-foreground" />
        <p>{forbidden}</p>
      </Card>
    );
  }
  return (
    <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
      {null}
    </DataState>
  );
}
