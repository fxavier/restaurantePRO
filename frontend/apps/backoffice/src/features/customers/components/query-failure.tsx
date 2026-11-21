import { ApiError } from '@restaurantpro/api-client';
import { DataState } from '@restaurantpro/ui';
import { isUnavailable } from '../../../lib/errors';

type QueryFailureProps = { error: unknown; onRetry: () => void; notFound?: string };

/** A read that failed (FC §9): not found and no permission are said as such; no connection and the rest offer a retry. */
export function QueryFailure({ error, onRetry, notFound }: QueryFailureProps) {
  if (error instanceof ApiError && error.status === 404 && notFound) return <p className="py-6 text-center">{notFound}</p>;
  if (error instanceof ApiError && error.status === 403) {
    return <p className="py-6 text-center">Sem permissão para ver estes dados.</p>;
  }
  return (
    <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
      {null}
    </DataState>
  );
}
