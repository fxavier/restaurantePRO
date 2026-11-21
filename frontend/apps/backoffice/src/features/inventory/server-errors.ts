import { ApiError } from '@restaurantpro/api-client';
import { errorMessage } from '../../lib/error-messages';

/** The inventory's own codes in Portuguese (FC §9); the shared list keeps the rest. */
export const inventoryMessages = {
  PO_NUMBER_TAKEN: 'Já existe uma encomenda com este número.',
  PO_STATE: 'A encomenda não permite esta operação no estado atual. Atualize e tente de novo.',
  RECEIPT_STATE: 'A receção não permite esta operação no estado atual. Atualize e tente de novo.',
  OVER_RECEIPT: 'A quantidade recebida ultrapassa a encomendada.',
  COUNT_STATE: 'A contagem não permite esta operação no estado atual. Atualize e tente de novo.',
  LIVE_COUNT_EXISTS: 'Este armazém já tem uma contagem em curso.',
  RECIPE_EXISTS: 'Já existe uma ficha técnica para este artigo.',
  RECIPE_VERSION_IMMUTABLE: 'Esta versão da ficha técnica já não pode ser alterada.',
  RECIPE_CYCLE: 'A ficha técnica não pode usar-se a si própria.',
  TRANSFER_STATE: 'A transferência não permite esta operação no estado atual. Atualize e tente de novo.',
  NEGATIVE_STOCK_REFUSED: 'Este armazém não permite stock negativo.',
  UNIT_COST_REQUIRED: 'Uma entrada de stock precisa do custo unitário.',
  REASON_REQUIRED: 'Indique o motivo.',
  INVALID_QUANTITY: 'Quantidade inválida.',
  DUPLICATE_STOCK_ITEM: 'O mesmo artigo aparece duas vezes.',
  UNKNOWN_UNIT: 'Unidade desconhecida.',
  UNIT_DIMENSION_MISMATCH: 'A unidade não é compatível com a do artigo.',
  RECEIVED_EXCEEDS_SHIPPED: 'A quantidade recebida ultrapassa a enviada.',
  INVENTORY_NOT_ACTIVE: 'O inventário desta loja ainda não está ativo.',
} as const;

export function inventoryError(error: unknown): string {
  return errorMessage(error, { messages: inventoryMessages });
}

export type FieldErrors = { fields: [field: string, message: string][]; others: string[] };

/**
 * A 400's field violations split into the form's fields and the rest. A line's field (`lines[0].unitCost`) lands on
 * the form's array field (`lines.0.unitCost`) when the form lists the array (`lines`), else on the form's field of
 * that name (a one-line dialog names it `unitCost`). `toForm` first rewrites a request field into the form's, when
 * the request leaves some of the form's lines out (a partial receipt).
 */
export function fieldErrors(
  error: unknown,
  fields: readonly string[],
  toForm: (field: string) => string = (field) => field,
): FieldErrors | undefined {
  if (!(error instanceof ApiError) || error.status !== 400 || !error.errors?.length) return undefined;
  const result: FieldErrors = { fields: [], others: [] };
  for (const { field, message } of error.errors) {
    const name = formField(toForm(field), fields);
    if (name) result.fields.push([name, message]);
    else result.others.push(message);
  }
  return result;
}

function formField(field: string, fields: readonly string[]): string | undefined {
  const dotted = field.replace(/\[(\d+)\]/g, '.$1');
  if (fields.some((name) => dotted === name || dotted.startsWith(`${name}.`))) return dotted;
  const single = field.replace(/^lines\[\d+\]\./, '');
  return fields.includes(single) ? single : undefined;
}
