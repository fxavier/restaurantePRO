import { ApiError } from '@restaurantpro/api-client';

/** 401 other than a step-up request: the BFF session is gone (BFF_SESSION_EXPIRED), so only a new login helps (FC §9). */
export function isSessionExpired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401 && error.code !== 'MFA_STEP_UP_REQUIRED';
}

/** No answer, or the BFF could not reach the backend or Keycloak: "Sem ligação" (FC §9, §11). */
export function isUnavailable(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status === 502 || error.status === 503);
}

/** 409 from the BFF: another tab switched the company this tab still shows; the write was not sent (FE-F16). */
export function isTenantChanged(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === 'TENANT_CHANGED';
}

export function errorCode(error: unknown): string | undefined {
  return error instanceof ApiError ? error.code : undefined;
}
