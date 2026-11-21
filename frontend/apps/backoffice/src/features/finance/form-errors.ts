import { ApiError } from '@restaurantpro/api-client';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';

export const MAYBE_CREATED = 'O primeiro envio pode já ter sido registado. Confira a lista antes de criar outro.';

/**
 * A create resent after a lost answer that the server refuses with a 409: the series and payment-method creates take
 * no Idempotency-Key, so the conflict may be with the first send itself. The list is already fetched again (the
 * command's onSettled runs first); the form says so instead of "already taken".
 */
export function isMaybeCreated(error: unknown, afterUnknown: boolean): boolean {
  return afterUnknown && error instanceof ApiError && error.status === 409;
}

/**
 * Puts a 400's field violations on the form's fields (§9: server validation on the matching field); the rest go to
 * the form's root. Returns whether the error was shown that way, so the caller does not explain it twice.
 */
export function showOnFields<Values extends FieldValues>(
  setError: UseFormSetError<Values>,
  fields: readonly string[],
  error: unknown,
): boolean {
  if (!(error instanceof ApiError) || error.status !== 400 || !error.errors?.length) return false;
  const others: string[] = [];
  for (const { field, message } of error.errors) {
    if (fields.includes(field)) setError(field as Path<Values>, { message });
    else others.push(message);
  }
  if (others.length) setError('root', { message: others.join(' ') });
  return true;
}
