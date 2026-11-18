import { z } from 'zod';

/** "Registar terminal": the contract's limits (`TerminalCreateRequest`), with Portuguese messages. */
export const registerTerminalSchema = z.object({
  code: z.string().trim().min(1, 'Indique o código.').max(20, 'O código tem no máximo 20 caracteres.'),
  name: z.string().trim().min(1, 'Indique o nome.').max(120, 'O nome tem no máximo 120 caracteres.'),
});

export type RegisterTerminalValues = z.infer<typeof registerTerminalSchema>;
