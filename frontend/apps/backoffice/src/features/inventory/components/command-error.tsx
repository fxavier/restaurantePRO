import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { inventoryError } from '../server-errors';

/**
 * A command's answer that is not a field error (FC §9). Without an answer the outcome is unknown, so "Tentar
 * novamente" resends the same request under the same key (FC §8); any other answer is final and only explained. A
 * TENANT_CHANGED reloads the page instead (FE-F16).
 */
export function CommandError({ error, onRetry, pending }: { error: unknown; onRetry: () => void; pending: boolean }) {
  if (!error || isTenantChanged(error)) return null;
  const unknownOutcome = isUnavailable(error) || isInProgress(error);
  const Icon = isUnavailable(error) ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{inventoryError(error)}</span>
        {unknownOutcome && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
