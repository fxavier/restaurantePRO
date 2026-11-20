import { ApiError } from '@restaurantpro/api-client';
import { errorMessage, type FeatureMessages } from '../../lib/error-messages';

/** The catalog's own Portuguese texts for backend codes the shared list does not word for this screen. */
export const CATALOG_MESSAGES: FeatureMessages = {
  VERSION_CONFLICT: 'Foi alterado por outra pessoa entretanto. Feche, reabra e tente de novo.',
  ILLEGAL_RELEASE_STATE: 'A versão já mudou de estado. A lista foi atualizada; reveja-a e tente de novo.',
  HTTP_422: 'O catálogo tem problemas que impedem a validação (por exemplo, artigos sem preço ou sem taxa de IVA). Corrija-os e tente de novo.',
};

/** The Portuguese text of a catalog command's failure. */
export function catalogErrorMessage(error: unknown): string {
  return errorMessage(error, { messages: CATALOG_MESSAGES });
}

/** The failure a form shows as a general error: any but one that lands on its fields (`placeOnFields`). */
export function nonFieldError(error: unknown, fieldNames: readonly string[]): unknown {
  return error && !placeOnFields(error, fieldNames) ? error : undefined;
}

/** Where a server answer belongs in a form: on named fields, or the whole answer is a general error. */
export type FieldPlacement = { fields: { name: string; message: string }[]; rest: string[] };

/**
 * A 400's field violations land on the form's own fields (the rest as one form-level message); a taken code lands on
 * `code` when the form has one. Anything else is not a field error: `undefined`.
 */
export function placeOnFields(error: unknown, fieldNames: readonly string[]): FieldPlacement | undefined {
  if (!(error instanceof ApiError)) return undefined;
  if (error.status === 409 && error.code === 'CATALOG_CODE_TAKEN' && fieldNames.includes('code')) {
    return { fields: [{ name: 'code', message: catalogErrorMessage(error) }], rest: [] };
  }
  if (error.status !== 400 || !error.errors?.length) return undefined;
  const placement: FieldPlacement = { fields: [], rest: [] };
  for (const { field, message } of error.errors) {
    if (fieldNames.includes(field)) placement.fields.push({ name: field, message });
    else placement.rest.push(message);
  }
  return placement;
}
