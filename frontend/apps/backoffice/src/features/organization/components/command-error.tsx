import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';

type CommandErrorProps = { error: unknown; onRetry: () => void; pending: boolean };

/**
 * A command's answer that is not a field error (FC §9). Without an answer, or still in progress after the resends, the
 * outcome is unknown, so "Tentar novamente" sends the same request again (FC §8); any other answer is final and only
 * explained. A 409
 * TENANT_CHANGED is not explained here: the page reloads for it (FE-F16, `lib/query-client`).
 */
export function CommandError({ error, onRetry, pending }: CommandErrorProps) {
  if (isTenantChanged(error)) return null;
  const unavailable = isUnavailable(error);
  const Icon = unavailable ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error)}</span>
        {(unavailable || isInProgress(error)) && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
