import { z } from 'zod';
import type { Permission, SupportGrantRequest } from './types';

/** A permission name as the contract lists them ("PRODUCTION_VIEW"); the catalogue the matrix offers holds only these. */
const PERMISSION_NAME = /^[A-Z][A-Z_]*$/;

const permission = z.custom<Permission>((value) => typeof value === 'string' && PERMISSION_NAME.test(value));

/** "Nova função" and "Editar" (`RoleCreateRequest`, `RoleUpdateRequest`): a name and the permissions it grants. */
export const roleSchema = z.object({
  name: z.string().trim().min(1, 'Indique o nome.').max(60, 'O nome tem no máximo 60 caracteres.'),
  permissions: z.array(permission),
});

export type RoleValues = z.infer<typeof roleSchema>;

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

/** "Editar" an employee (`EmployeeUpdateRequest` without its version): the name, and whether they still work here. */
export const employeeSchema = z.object({
  name: newEmployeeSchema.shape.name,
  active: z.boolean(),
});

export type EmployeeValues = z.infer<typeof employeeSchema>;

/** "Nova conta" and "Editar" (`UserCreateRequest`, `UserUpdateRequest`); the username is fixed once created. */
export const accountSchema = z.object({
  username: z
    .string()
    .trim()
    .min(1, 'Indique o nome de utilizador.')
    .max(254, 'O nome de utilizador tem no máximo 254 caracteres.')
    .regex(/^[A-Za-z0-9._@+-]+$/, 'Use só letras, algarismos e . _ @ + -'),
  active: z.boolean(),
  support: z.boolean(),
  roleIds: z.array(z.string()),
  employeeId: z.string(),
});

/** "Conceder acesso de suporte" (`SupportGrantRequest`): at most 24 hours (P36, D83). */
export const grantSchema = z.object({
  accountId: z.string().min(1, 'Escolha a conta de suporte.'),
  subject: z.string().trim().min(1, 'Indique o identificador.').max(255, 'O identificador tem no máximo 255 caracteres.'),
  // The input's text; the request sends it as a number (`toGrantRequest`).
  durationMinutes: z
    .string()
    .refine((text) => /^\d{1,4}$/.test(text) && Number(text) >= 1 && Number(text) <= 1440, {
      message: 'A duração vai de 1 a 1440 minutos (24 horas).',
    }),
  reason: z.string().trim().min(1, 'Indique o motivo.').max(500, 'O motivo tem no máximo 500 caracteres.'),
});

export type GrantValues = z.infer<typeof grantSchema>;

export function toGrantRequest(values: GrantValues): SupportGrantRequest {
  return { ...values, durationMinutes: Number(values.durationMinutes) };
}

/** "Atribuir" (`AssignmentRequest`): a role in a store. */
export const assignmentSchema = z.object({
  storeId: z.string().min(1, 'Escolha a loja.'),
  roleId: z.string().min(1, 'Escolha a função.'),
});

export type AssignmentValues = z.infer<typeof assignmentSchema>;

/** "Repor PIN" (`PinResetRequest`). */
export const resetPinSchema = z.object({ pin });

export type ResetPinValues = z.infer<typeof resetPinSchema>;
