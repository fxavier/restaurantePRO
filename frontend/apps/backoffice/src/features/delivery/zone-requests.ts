import type { ZoneValues } from './schemas';
import type { ZoneCreateRequest, ZoneUpdateRequest, ZoneView } from './types';

/** The form's values for a zone being edited, or empty ones for a new zone (active from the start). */
export function zoneValues(zone?: ZoneView): ZoneValues {
  const { code = '', name = '', feeItemId = '', feeVariantId = '', active = true } = zone ?? {};
  return { code, name, feeItemId, feeVariantId, active };
}

/** The variant is sent only when one is chosen: '' in the form is "the item itself". */
function fee({ feeItemId, feeVariantId }: ZoneValues) {
  return feeVariantId ? { feeItemId, feeVariantId } : { feeItemId };
}

export function toCreateRequest(storeId: string, values: ZoneValues): ZoneCreateRequest {
  return { storeId, code: values.code, name: values.name, ...fee(values) };
}

/** The zone's `version` guards the change (409 when someone changed it meanwhile). */
export function toUpdateRequest(values: ZoneValues, version: number): ZoneUpdateRequest {
  return { name: values.name, ...fee(values), active: values.active, version };
}

export type Intent<Body> = { body: Body; key: string };

/**
 * One Idempotency-Key per intent (FC §8): the same request again keeps the previous key, a corrected one is a new
 * intent with a new key.
 */
export function intentFor<Body>(previous: Intent<Body> | undefined, body: Body, newKey: () => string): Intent<Body> {
  return previous && JSON.stringify(previous.body) === JSON.stringify(body) ? previous : { body, key: newKey() };
}
