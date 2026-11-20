import { z } from 'zod';
import { CODE_PAGES } from './parse';

/** "Adicionar impressora" / "Configurar": the contract's limits (`PrinterCreateRequest`), with Portuguese messages. */
export const printerSchema = z.object({
  // Shown in capitals, so sent in capitals: the store's code uniqueness is case-sensitive.
  code: z.string().trim().min(1, 'Indique o código.').max(20, 'O código tem no máximo 20 caracteres.').toUpperCase(),
  name: z.string().trim().min(1, 'Indique o nome.').max(120, 'O nome tem no máximo 120 caracteres.'),
  host: z.string().trim().min(1, 'Indique o endereço da impressora.').max(255, 'O endereço tem no máximo 255 caracteres.'),
  // Kept as typed (a text field), sent as a number by `printer-requests`.
  port: z
    .string()
    .trim()
    .refine((port) => /^\d{1,5}$/.test(port) && Number(port) >= 1 && Number(port) <= 65535, 'A porta vai de 1 a 65535.'),
  codePage: z.enum(CODE_PAGES),
  /** A preset's name (create only): its settings go with the printer; empty sends none, the server's default. */
  preset: z.string(),
  /** Edit only: an inactive printer stays configured but takes no work. */
  active: z.boolean(),
});

export type PrinterFormValues = z.infer<typeof printerSchema>;
