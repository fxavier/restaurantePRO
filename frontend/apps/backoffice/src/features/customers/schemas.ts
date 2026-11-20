import { z } from 'zod';

const reason = z.string().trim().min(1, 'Indique o motivo.').max(500, 'O motivo tem no máximo 500 caracteres.');

/**
 * An amount as the user types it ("1500", "1500,5", "1500.50"), sent as the contract's decimal string with a dot. Never
 * turned into a number (FC §12).
 */
const amount = z
  .string()
  .trim()
  .regex(/^\d{1,13}([.,]\d{1,2})?$/, 'Indique um valor como 1500,00.')
  .transform((value) => value.replace(',', '.'));

/** "Alterar limite" (CreditLimitChangeRequest): the new limit and why. */
export const creditLimitSchema = z.object({ newLimit: amount, reason });
export type CreditLimitValues = z.input<typeof creditLimitSchema>;

/** "Bloquear crédito" (CreditBlockRequest) and the loyalty suspend and resume (ReasonRequest): a reason is required. */
export const reasonSchema = z.object({ reason });
export type ReasonValues = z.input<typeof reasonSchema>;

/** "Desbloquear crédito" (CreditUnblockRequest): the reason is optional. */
export const optionalReasonSchema = z.object({
  reason: z.string().trim().max(500, 'O motivo tem no máximo 500 caracteres.'),
});

/** An optional text: blank is not sent (an update without it clears the field). */
const optionalText = (max: number, message: string) =>
  z
    .string()
    .trim()
    .max(max, message)
    .transform((value) => value || undefined);

const name = z.string().trim().min(1, 'Indique o nome.').max(160, 'O nome tem no máximo 160 caracteres.');
const taxId = optionalText(30, 'O NIF tem no máximo 30 caracteres.');
const email = optionalText(254, 'O email tem no máximo 254 caracteres.').refine(
  (value) => value === undefined || z.email().safeParse(value).success,
  'Indique um email válido.',
);
const notes = optionalText(2000, 'As notas têm no máximo 2000 caracteres.');

/** "Novo cliente" (CustomerCreateRequest): only the name is required; the phone becomes the primary one. */
export const newCustomerSchema = z.object({
  name,
  taxId,
  email,
  phone: optionalText(40, 'O telefone tem no máximo 40 caracteres.'),
  notes,
});
export type NewCustomerValues = z.input<typeof newCustomerSchema>;

/** "Editar dados" (CustomerUpdateRequest), with the version the data was read at (a stale one is a 409). */
export const customerSchema = z.object({ name, taxId, email, notes, active: z.boolean(), version: z.number() });
export type CustomerValues = z.input<typeof customerSchema>;

/**
 * "Ajustar pontos" (AdjustmentRequest, §8.4): a whole number of points, a debit with a minus sign, never zero; and why.
 * The store is the one picked in the top bar, added by the command.
 */
export const adjustmentSchema = z.object({
  points: z
    .string()
    .trim()
    .regex(/^[-+]?\d{1,9}$/, 'Indique um número inteiro de pontos, diferente de zero.')
    .transform(Number)
    .refine((points) => points !== 0, 'Indique um número inteiro de pontos, diferente de zero.'),
  reason,
});
