import { ApiError, type FieldError } from '@restaurantpro/api-client';

/** A 409 code that names one field, e.g. a taken code: the field and the Portuguese text it shows. */
export type TakenCodes<Field extends string> = Readonly<Record<string, { field: Field; message: string }>>;

export type FieldErrors<Field extends string> = { fields: Partial<Record<Field, string>>; rest: string[] };

/**
 * What of a command's answer belongs on the form's fields (FC §9): a 400's violations, each on its field (the first
 * one wins) or else in `rest`, and a 409 whose code names a field. Undefined when the answer is about no field.
 */
export function fieldErrors<Field extends string>(
  error: unknown,
  fields: readonly Field[],
  taken: TakenCodes<Field> = {},
): FieldErrors<Field> | undefined {
  if (!(error instanceof ApiError)) return undefined;
  if (error.status === 409) return takenField(taken[error.code]);
  if (error.status === 400 && error.errors?.length) return violations(error.errors, fields);
  return undefined;
}

function takenField<Field extends string>(named: TakenCodes<Field>[string] | undefined): FieldErrors<Field> | undefined {
  return named && { fields: { [named.field]: named.message } as Partial<Record<Field, string>>, rest: [] };
}

function violations<Field extends string>(errors: readonly FieldError[], fields: readonly Field[]): FieldErrors<Field> {
  const result: FieldErrors<Field> = { fields: {}, rest: [] };
  for (const { field, message } of errors) {
    if (isField(fields, field) && result.fields[field] === undefined) result.fields[field] = message;
    else result.rest.push(message);
  }
  return result;
}

function isField<Field extends string>(fields: readonly Field[], name: string): name is Field {
  return (fields as readonly string[]).includes(name);
}

/** 409 VERSION_CONFLICT: someone saved a newer version since the form was loaded (FC §9). */
export function isVersionConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === 'VERSION_CONFLICT';
}
