import type { components } from '@restaurantpro/api-client';

type ConfirmBatchRequest = components['schemas']['com.xavier.backend.orders.model.dto.ConfirmBatchRequest'];

/** A round as "Pedir" sends it; `expectedGross` a plain decimal string, written into the JSON as is. */
export type ConfirmBatch = Omit<ConfirmBatchRequest, 'expectedGross'> & { expectedGross?: string };

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

/**
 * The exact sum of server amounts, as a plain decimal string (FC §12's one exception: `expectedGross`, a concurrency
 * guard echoed back to the server, never shown and never authoritative). Each amount is scaled to the largest number
 * of decimals present and added as a BigInt: no floating point anywhere. A JSON number arrives as a JS number whose
 * shortest form is the decimal the server wrote; an exponent form is refused.
 */
export function sumDecimals(amounts: readonly (number | string)[]): string {
  const parsed = amounts.map((amount) => {
    const match = DECIMAL.exec(String(amount));
    if (!match) throw new Error('Not a decimal amount');
    const [, sign, whole, fraction = ''] = match;
    return { negative: sign === '-', whole: whole!, fraction };
  });
  const scale = Math.max(0, ...parsed.map(({ fraction }) => fraction.length));
  let total = 0n;
  for (const { negative, whole, fraction } of parsed) {
    const units = BigInt(whole + fraction.padEnd(scale, '0'));
    total += negative ? -units : units;
  }
  const negative = total < 0n;
  const digits = (negative ? -total : total).toString().padStart(scale + 1, '0');
  const whole = digits.slice(0, digits.length - scale);
  const fraction = digits.slice(digits.length - scale);
  return `${negative ? '-' : ''}${whole}${scale > 0 ? `.${fraction}` : ''}`;
}

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
