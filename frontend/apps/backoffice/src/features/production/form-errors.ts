import { ApiError } from '@restaurantpro/api-client';
import { errorMessage } from '../../lib/error-messages';
import { productionMessages } from './messages';

export type FieldErrors<Field extends string> = { fields: Partial<Record<Field, string>>; others: string[] };

/**
 * The part of a refused command that belongs on the form (FC §9): a 400's `errors[]` on the matching fields and the
 * rest as one form-level text, or a conflict code that names a field (a taken code). Undefined for any other answer,
 * which the form shows as a general error.
 */
export function fieldErrors<Field extends string>(
  error: unknown,
  fields: readonly Field[],
  codeFields: Readonly<Record<string, Field>> = {},
): FieldErrors<Field> | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const onField = codeFields[error.code];
  if (onField) return { fields: { [onField]: errorMessage(error, { messages: productionMessages }) } as FieldErrors<Field>['fields'], others: [] };
  if (error.status !== 400 || !error.errors?.length) return undefined;
  const result: FieldErrors<Field> = { fields: {}, others: [] };
  for (const { field, message } of error.errors) {
    if ((fields as readonly string[]).includes(field)) result.fields[field as Field] = message;
    else result.others.push(message);
  }
  return result;
}
