import { ApiError } from '@restaurantpro/api-client';
import { DataState } from '@restaurantpro/ui';
import { isUnavailable } from '../../../lib/errors';

type ReadFailureProps = { error: unknown; onRetry: () => void; forbidden: string; notFound?: string };

/** A read that failed (FC §9): no permission or not found said as such; no connection and the rest offer a retry. */
export function ReadFailure({ error, onRetry, forbidden, notFound }: ReadFailureProps) {
  const status = error instanceof ApiError ? error.status : undefined;
  if (status === 403) return <p className="py-6 text-center">{forbidden}</p>;
  if (status === 404 && notFound) return <p className="py-6 text-center">{notFound}</p>;
  return (
    <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
      {null}
    </DataState>
  );
}
