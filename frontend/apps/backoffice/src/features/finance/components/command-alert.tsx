import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { FINANCE_MESSAGES } from '../finance-messages';

type CommandAlertProps = { error: unknown; pending: boolean; onRetry: () => void };

/**
 * A finance command's failure (FC §9). Without an answer the outcome is unknown, so "Tentar novamente" resends the
 * same request under the same key (FC §8); any other answer is final and explained. A 409 TENANT_CHANGED reloads the
 * page instead (FE-F16).
 */
export function CommandAlert({ error, pending, onRetry }: CommandAlertProps) {
  if (isTenantChanged(error)) return null;
  const unknown = isUnavailable(error) || isInProgress(error);
  const Icon = isUnavailable(error) ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error, { messages: FINANCE_MESSAGES })}</span>
        {unknown && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
