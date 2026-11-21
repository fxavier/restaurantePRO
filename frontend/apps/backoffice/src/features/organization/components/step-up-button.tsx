import type { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { ShieldCheck } from 'lucide-react';
import { useLocation } from 'react-router';
import { browser } from '../../../lib/browser';
import { stepUpUrl } from '../../../lib/step-up';

/**
 * The hop to Keycloak for a fresh second factor (D184), back to this page. Nothing is kept for a resume: these
 * configuration edits are short, so the user opens the form again on return (FC §6 resumes only terminal commands).
 */
export function StepUpButton({ error }: { error: ApiError }) {
  const { pathname, search } = useLocation();
  return (
    <Button type="button" variant="outline" onClick={() => browser.assign(stepUpUrl(error, `${pathname}${search}`))}>
      <ShieldCheck aria-hidden="true" />
      Confirmar identidade
    </Button>
  );
}
