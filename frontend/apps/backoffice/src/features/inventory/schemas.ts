import { z } from 'zod';
import { isPositive, toDecimal } from './decimal';

const QUANTITY = 'Indique uma quantidade, por exemplo 2,5.';
const POSITIVE = 'Indique uma quantidade maior que zero.';
const COST = 'Indique um custo, por exemplo 9,80.';

/** A decimal typed in pt, sent as the contract's decimal string (FC §12). */
const decimal = (message: string) =>
  z.string().transform((text, context) => {
    const value = toDecimal(text);
    if (value === undefined) context.addIssue({ code: 'custom', message });
    return value ?? '';
  });
const positive = (message: string) => decimal(message).refine(isPositive, POSITIVE);
/** An optional decimal: empty is absent. */
const optionalDecimal = (message: string) =>
  z.string().transform((text, context) => {
    if (!text.trim()) return undefined;
    const value = toDecimal(text);
    if (value === undefined) context.addIssue({ code: 'custom', message });
    return value;
  });
const pick = (message: string) => z.string().min(1, message);
const text = (max: number, message: string) => z.string().trim().max(max, message);

/** "Registar quebra" (waste, a positive quantity out) and "Ajustar stock" (a signed quantity; an entry needs a cost). */
export const movementSchema = z.object({
  stockItemId: pick('Escolha o artigo.'),
  quantity: decimal(QUANTITY).refine((value) => value === '' || /[1-9]/.test(value), 'A quantidade não pode ser zero.'),
  unitCost: optionalDecimal(COST),
  reason: text(500, 'O motivo tem no máximo 500 caracteres.'),
});
export const wasteSchema = movementSchema.refine((values) => isPositive(values.quantity), {
  path: ['quantity'],
  message: POSITIVE,
});
export type MovementInput = z.input<typeof movementSchema>;
export type MovementValues = z.output<typeof movementSchema>;

export const supplierSchema = z.object({
  code: z.string().trim().min(1, 'Indique o código.').max(40, 'O código tem no máximo 40 caracteres.'),
  name: z.string().trim().min(1, 'Indique o nome.').max(160, 'O nome tem no máximo 160 caracteres.'),
  taxId: text(40, 'O número de contribuinte tem no máximo 40 caracteres.'),
  phone: text(40, 'O telefone tem no máximo 40 caracteres.'),
  email: z.union([z.literal(''), z.email('Indique um email válido.').max(200)]),
});
export type SupplierInput = z.input<typeof supplierSchema>;
export type SupplierValues = z.output<typeof supplierSchema>;

const orderLine = z.object({
  stockItemId: pick('Escolha o artigo.'),
  quantity: positive(QUANTITY),
  unitCost: optionalDecimal(COST),
});

export const purchaseOrderSchema = z.object({
  number: z.string().trim().min(1, 'Indique o número.').max(30, 'O número tem no máximo 30 caracteres.'),
  supplierId: pick('Escolha o fornecedor.'),
  warehouseId: pick('Escolha o armazém.'),
  expectedAt: z.string(),
  lines: z.array(orderLine).min(1, 'Acrescente pelo menos um artigo.'),
});
export type PurchaseOrderInput = z.input<typeof purchaseOrderSchema>;
export type PurchaseOrderValues = z.output<typeof purchaseOrderSchema>;

export const receiptSchema = z.object({
  supplierDocumentRef: text(60, 'A referência tem no máximo 60 caracteres.'),
  lines: z.array(z.object({ purchaseOrderLineId: z.string(), stockItemId: z.string(), packQuantity: decimal(QUANTITY) })),
});
export type ReceiptInput = z.input<typeof receiptSchema>;
export type ReceiptValues = z.output<typeof receiptSchema>;

export const recipeSchema = z.object({
  name: z.string().trim().min(1, 'Indique o nome.').max(160, 'O nome tem no máximo 160 caracteres.'),
});
export type RecipeValues = z.infer<typeof recipeSchema>;

export const recipeVersionSchema = z.object({
  yieldQuantity: positive(QUANTITY),
  yieldUnit: z.string().trim().min(1, 'Indique a unidade.').max(10, 'A unidade tem no máximo 10 caracteres.'),
  ingredients: z
    .array(
      z.object({
        stockItemId: pick('Escolha o artigo.'),
        quantity: positive(QUANTITY),
        unit: z.string().trim().min(1, 'Indique a unidade.').max(10, 'A unidade tem no máximo 10 caracteres.'),
      }),
    )
    .min(1, 'Acrescente pelo menos um ingrediente.'),
});
export type RecipeVersionInput = z.input<typeof recipeVersionSchema>;
export type RecipeVersionValues = z.output<typeof recipeVersionSchema>;

export const countLineSchema = z.object({ countedQuantity: decimal(QUANTITY) });
