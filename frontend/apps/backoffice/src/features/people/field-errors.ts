import { ApiError } from '@restaurantpro/api-client';
import { errorMessage } from '../../lib/error-messages';
import { peopleMessages } from './messages';

export type FieldErrorAssignment = { field: string; message: string };

/**
 * Where a refused command's answer belongs in its form (FC §9): a 400's violations on the matching fields and the rest
 * on the form (`root`), and a conflict whose code names one field (a taken code) on that field. Undefined when the
 * answer is about the command, not its input: the caller explains it outside the fields.
 */
export function fieldErrors(
  error: unknown,
  fields: readonly string[],
  conflicts: Readonly<Record<string, string>> = {},
): FieldErrorAssignment[] | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const conflictField = conflicts[error.code];
  if (conflictField) return [{ field: conflictField, message: errorMessage(error, { messages: peopleMessages }) }];
  if (error.status !== 400 || !error.errors?.length) return undefined;
  const others = error.errors.filter(({ field }) => !fields.includes(field)).map(({ message }) => message);
  return [
    ...error.errors.filter(({ field }) => fields.includes(field)),
    ...(others.length ? [{ field: 'root', message: others.join(' ') }] : []),
  ];
}
