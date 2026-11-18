import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isUnavailable } from '../../../lib/errors';

type CommandErrorProps = { error: unknown; onRetry: () => void; pending: boolean };

/**
 * A command's answer that is not a field error (FC §9). Without an answer the outcome is unknown, so "Tentar
 * novamente" sends the same request again (FC §8); any other answer is final and only explained.
 */
export function CommandError({ error, onRetry, pending }: CommandErrorProps) {
  const unavailable = isUnavailable(error);
  const Icon = unavailable ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error)}</span>
        {unavailable && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
