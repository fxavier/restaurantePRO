import { ApiError } from '@restaurantpro/api-client';
import type { FeatureMessages } from '../../lib/error-messages';
import type { ZoneValues } from './schemas';

/** The delivery feature's Portuguese texts for its backend codes (FC §9); the shared list stays untouched. */
export const deliveryMessages: FeatureMessages = {
  DELIVERY_ZONE_CODE_TAKEN: 'Já existe uma zona com este código nesta loja.',
};

export type FieldMessage = { field: keyof ZoneValues | 'root'; message: string };

const FIELDS = new Set<string>(['code', 'name', 'feeItemId', 'feeVariantId', 'active']);

/**
 * Where a refused zone command's answer belongs on the form: a taken code on the code field; a 400's violations on
 * their fields, the others together as one form-level message. Undefined when the answer is not about the fields.
 */
export function onFields(error: unknown): FieldMessage[] | undefined {
  if (!(error instanceof ApiError)) return undefined;
  if (error.status === 409 && error.code === 'DELIVERY_ZONE_CODE_TAKEN') {
    return [{ field: 'code', message: deliveryMessages.DELIVERY_ZONE_CODE_TAKEN! }];
  }
  if (error.status !== 400 || !error.errors?.length) return undefined;
  const placed: FieldMessage[] = [];
  const others: string[] = [];
  for (const { field, message } of error.errors) {
    if (FIELDS.has(field)) placed.push({ field: field as keyof ZoneValues, message });
    else others.push(message);
  }
  return others.length ? [...placed, { field: 'root', message: others.join(' ') }] : placed;
}
