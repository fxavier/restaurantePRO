import { Alert, AlertDescription, Button } from '@restaurantpro/ui';
import { ShieldCheck, TriangleAlert, WifiOff } from 'lucide-react';
import { useLocation } from 'react-router';
import { browser } from '../../../lib/browser';
import { errorMessage } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isInProgress } from '../../../lib/in-progress';
import { isStepUpRequired, stepUpUrl } from '../../../lib/step-up';
import { customerMessages } from '../messages';

type CommandFeedbackProps = { error: unknown; pending: boolean; onRetry: () => void };

/**
 * A command's failure (FC §9). A step-up goes to the second factor and back to this customer, where the user sends
 * the command again (these commands are not resumed by `/step-up/resume`). Without an answer, or still in progress,
 * the outcome is unknown: "Tentar novamente" resends the same request under the same key (FC §8). Any other answer is
 * final and only explained. TENANT_CHANGED reloads the page (FE-F16), so it says nothing here.
 */
export function CommandFeedback({ error, pending, onRetry }: CommandFeedbackProps) {
  const { pathname, search } = useLocation();
  if (!error || isTenantChanged(error)) return null;
  if (isStepUpRequired(error)) {
    return (
      <Alert>
        <ShieldCheck aria-hidden="true" />
        <AlertDescription className="flex flex-wrap items-center gap-3">
          <span>Esta ação pede que confirme a sua identidade. Depois, volta a este cliente para a repetir.</span>
          <Button type="button" variant="outline" size="sm" onClick={() => browser.assign(stepUpUrl(error, `${pathname}${search}`))}>
            Confirmar identidade
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
  const unavailable = isUnavailable(error);
  const Icon = unavailable ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{errorMessage(error, { messages: customerMessages })}</span>
        {(unavailable || isInProgress(error)) && (
          <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={pending}>
            Tentar novamente
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
