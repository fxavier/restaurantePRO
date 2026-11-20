import { z } from 'zod';

/** A catalog code: the contract's length, sent in capitals as shown (codes are case-sensitive on the server). */
const code = (max: number) =>
  z.string().trim().min(1, 'Indique o código.').max(max, `O código tem no máximo ${max} caracteres.`).toUpperCase();
const name = (max: number) => z.string().trim().min(1, 'Indique o nome.').max(max, `O nome tem no máximo ${max} caracteres.`);
/** An optional reference from a select: '' is none. */
const optionalId = z.string();

/** "Nova família" / "Editar família" (`FamilyCreateRequest`, `FamilyUpdateRequest`). */
export const familySchema = z.object({ code: code(30), name: name(120), parentId: optionalId });
export type FamilyValues = z.infer<typeof familySchema>;

const required = (message: string) => z.string().min(1, message);

/** "Adicionar variante" (`ItemVariantCreateRequest`). */
export const variantSchema = z.object({ code: code(30), name: name(120) });
export type VariantValues = z.infer<typeof variantSchema>;

/** "Adicionar código" (`BarcodeCreateRequest`): the code as scanned, the variant optional ('' is the item itself). */
export const barcodeSchema = z.object({
  code: z.string().trim().min(1, 'Indique o código de barras.').max(64, 'O código de barras tem no máximo 64 caracteres.'),
  variantId: optionalId,
});
export type BarcodeValues = z.infer<typeof barcodeSchema>;

/** "Novo artigo" / "Editar artigo" (`ItemCreateRequest`, `ItemUpdateRequest`); availability only matters on edit. */
export const itemSchema = z.object({
  code: code(30),
  name: name(120),
  familyId: required('Escolha a família.'),
  unitId: required('Escolha a unidade.'),
  taxCategoryId: required('Escolha a categoria de IVA.'),
  productionDestination: z.string(),
  soldByWeight: z.boolean(),
  availability: z.enum(['ACTIVE', 'INACTIVE']),
});
export type ItemValues = z.infer<typeof itemSchema>;
