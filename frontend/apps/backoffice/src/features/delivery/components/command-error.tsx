import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { deliveryMessages } from '../zone-errors';

type CommandErrorProps = { error: unknown; onRetry: () => void; pending: boolean };

/**
 * A zone command's answer that is not a field error (FC §9). Without an answer the outcome is unknown, so "Tentar
 * novamente" sends the same request again (FC §8); any other answer is final and only explained. TENANT_CHANGED
 * reloads the page instead (FE-F16).
 */
export function CommandError({ error, onRetry, pending }: CommandErrorProps) {
  if (isTenantChanged(error)) return null;
  const unknownOutcome = isUnavailable(error) || isInProgress(error);
  const Icon = isUnavailable(error) ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error, { messages: deliveryMessages })}</span>
        {unknownOutcome && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
