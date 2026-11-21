import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { isStepUpRequired } from '../../../lib/step-up';
import { peopleMessages } from '../messages';

type CommandErrorProps = { error: unknown; pending: boolean; onRetry?: () => void };

/**
 * A people command's answer that is neither a step-up (its own dialog) nor a field error (FC §9). Without an answer the
 * outcome is unknown, so "Tentar novamente" sends the same request again when the caller offers it (FC §8); any other
 * answer is final and explained. A 409 TENANT_CHANGED reloads the page (FE-F16) and is not explained here.
 */
export function CommandError({ error, pending, onRetry }: CommandErrorProps) {
  if (!error || isStepUpRequired(error) || isTenantChanged(error)) return null;
  const unavailable = isUnavailable(error);
  const Icon = unavailable ? WifiOff : TriangleAlert;
  const retry = onRetry && (unavailable || isInProgress(error));
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error, { messages: peopleMessages })}</span>
        {retry && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
