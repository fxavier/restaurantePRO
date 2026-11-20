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
