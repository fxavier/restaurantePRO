import { ApiError } from '@restaurantpro/api-client';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';

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
