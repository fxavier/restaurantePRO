import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { productionMessages } from '../messages';

type ProblemAlertProps = { error: unknown; pending: boolean; onRetry: () => void };

/**
 * A production command's refusal in Portuguese (FC §9). With no answer, or still in progress, the outcome is unknown
 * and "Tentar novamente" sends the same request (and key) again (FC §8); any other answer is final. TENANT_CHANGED
 * reloads the page instead (FE-F16).
 */
export function ProblemAlert({ error, pending, onRetry }: ProblemAlertProps) {
  if (isTenantChanged(error)) return null;
  const outcomeUnknown = isUnavailable(error) || isInProgress(error);
  return (
    <Alert variant="destructive">
      {isUnavailable(error) ? <WifiOff aria-hidden="true" /> : <TriangleAlert aria-hidden="true" />}
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error, { messages: productionMessages })}</span>
        {outcomeUnknown && (
          <Button type="button" variant="outline" size="sm" disabled={pending} onClick={onRetry}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
