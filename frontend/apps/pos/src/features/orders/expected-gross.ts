import type { components } from '@restaurantpro/api-client';

type ConfirmBatchRequest = components['schemas']['com.xavier.backend.orders.model.dto.ConfirmBatchRequest'];

/** A round as "Pedir" sends it; `expectedGross` the order's `draftGross` amount as a string, written into the JSON as is. */
export type ConfirmBatch = Omit<ConfirmBatchRequest, 'expectedGross'> & { expectedGross?: string };

/** A plain decimal; a JSON number arrives as a JS number whose shortest form is the decimal the server wrote. */
const DECIMAL = /^-?\d+(?:\.\d+)?$/;

/**
 * The request's JSON with `expectedGross` written as the exact decimal it is (a JSON number, as the contract types
 * it), never passed through a float.
 */
export function batchJson({ expectedGross, ...rest }: ConfirmBatch): string {
  const json = JSON.stringify(rest);
  if (expectedGross === undefined) return json;
  if (!DECIMAL.test(expectedGross)) throw new Error('Not a decimal amount');
  return `${json === '{}' ? '{' : `${json.slice(0, -1)},`}"expectedGross":${expectedGross}}`;
}
