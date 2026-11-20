import { z } from 'zod';

/** A POS PIN as `EmployeeCreateRequest` and `PinResetRequest` take it: 4 to 8 digits. */
const pin = z.string().regex(/^\d{4,8}$/, 'O PIN tem de ter 4 a 8 algarismos.');

/** "Novo empregado": the contract's limits (`EmployeeCreateRequest`), with Portuguese messages. */
export const newEmployeeSchema = z.object({
  code: z
    .string()
    .trim()
    .min(1, 'Indique o código.')
    .max(20, 'O código tem no máximo 20 caracteres.')
    .regex(/^[A-Za-z0-9_-]+$/, 'Use só letras, algarismos, «-» e «_».'),
  name: z.string().trim().min(1, 'Indique o nome.').max(120, 'O nome tem no máximo 120 caracteres.'),
  pin,
  storeId: z.string().min(1, 'Escolha a loja.'),
  roleId: z.string().min(1, 'Escolha a função.'),
});

export type NewEmployeeValues = z.infer<typeof newEmployeeSchema>;

/** "Repor PIN" (`PinResetRequest`). */
export const resetPinSchema = z.object({ pin });

export type ResetPinValues = z.infer<typeof resetPinSchema>;
