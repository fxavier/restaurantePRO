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
