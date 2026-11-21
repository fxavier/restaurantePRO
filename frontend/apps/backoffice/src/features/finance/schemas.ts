import { z } from 'zod';

const reason = z.string().trim().min(1, 'Indique o motivo.').max(500, 'No máximo 500 caracteres.');

export const rejectClosingSchema = z.object({ reason });
export type RejectClosingValues = z.infer<typeof rejectClosingSchema>;

/** The approver's PIN goes only into the request's header; the form keeps it while open, nothing else does. */
export const reopenDaySchema = z.object({
  reason,
  approverCode: z.string().trim().min(1, 'Indique o código do aprovador.').max(20, 'No máximo 20 caracteres.'),
  approverPin: z.string().regex(/^\d{4,8}$/, 'O PIN tem de 4 a 8 algarismos.'),
});
export type ReopenDayValues = z.infer<typeof reopenDaySchema>;

export const seriesSchema = z.object({
  code: z.string().trim().min(1, 'Indique o código.').max(20, 'No máximo 20 caracteres.'),
  /** As typed; empty lets the server start at 1. */
  firstNumber: z.string().trim().regex(/^(\d{1,9})?$/, 'Indique um número inteiro positivo.'),
});
export type SeriesValues = z.infer<typeof seriesSchema>;

export const METHOD_KINDS = ['CASH', 'EXTERNAL_TERMINAL', 'INTEGRATED'] as const;

const name = z.string().trim().min(1, 'Indique o nome.').max(120, 'No máximo 120 caracteres.');
const providerCode = z.string().trim().max(60, 'No máximo 60 caracteres.');

export const methodSchema = z.object({
  code: z.string().trim().min(1, 'Indique o código.').max(30, 'No máximo 30 caracteres.'),
  name,
  kind: z.enum(METHOD_KINDS, 'Escolha o tipo.'),
  providerCode,
  active: z.boolean(),
});
export type MethodValues = z.infer<typeof methodSchema>;
