import { Alert, AlertDescription } from '@restaurantpro/ui';
import { ShieldCheck, TriangleAlert, WifiOff } from 'lucide-react';
import { errorMessage, type FeatureMessages } from '../../../lib/error-messages';
import { isTenantChanged, isUnavailable } from '../../../lib/errors';
import { isStepUpRequired } from '../../../lib/step-up';
import { StepUpButton } from './step-up-button';

/**
 * A configuration command's answer that is not about a field (FC §9). These commands carry no Idempotency-Key, so
 * nothing is resent for the user: without an answer the outcome is unknown, the list is read again, and the user
 * decides. A step-up offers the hop; a 409 TENANT_CHANGED reloads the page instead (FE-F16).
 */
export function FormError({ error, messages }: { error: unknown; messages?: FeatureMessages }) {
  if (error === undefined || isTenantChanged(error)) return null;
  if (isStepUpRequired(error)) {
    return (
      <Alert>
        <ShieldCheck aria-hidden="true" />
        <AlertDescription className="flex flex-wrap items-center gap-3">
          <span>Esta alteração pede que confirme a sua identidade. Depois, volte a fazê-la.</span>
          <StepUpButton error={error} />
        </AlertDescription>
      </Alert>
    );
  }
  const Icon = isUnavailable(error) ? WifiOff : TriangleAlert;
  return (
    <Alert variant="destructive">
      <Icon aria-hidden="true" />
      <AlertDescription>{errorMessage(error, messages ? { messages } : {})}</AlertDescription>
    </Alert>
  );
}

/** The root error a 400 left for no field. */
export function RootError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return (
    <p role="alert" className="text-sm text-error">
      {message}
    </p>
  );
}
