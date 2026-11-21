import { ApiError } from '@restaurantpro/api-client';
import { DataState } from '@restaurantpro/ui';
import { Lock } from 'lucide-react';
import { isUnavailable } from '../../../lib/errors';

/**
 * A read that failed, in its own section so the rest of the screen stays usable: no permission in this store (the API
 * decides per store), no connection, anything else; the last two offer a retry.
 */
export function SectionError({ error, subject, onRetry }: { error: unknown; subject: string; onRetry: () => void }) {
  if (error instanceof ApiError && error.status === 403) {
    return (
      <p className="flex items-center gap-2 py-6 text-muted-foreground">
        <Lock aria-hidden="true" className="size-5 shrink-0" />
        Sem permissão para ver {subject} desta loja.
      </p>
    );
  }
  return (
    <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
      {null}
    </DataState>
  );
}
