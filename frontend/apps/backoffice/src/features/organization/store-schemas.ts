import { z } from 'zod';
import { isTimeZone } from './store-values';

const HH_MM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** "Nova loja" / "Editar loja": the contract's limits (`StoreCreateRequest`, `StoreUpdateRequest`), in Portuguese. */
export const storeSchema = z.object({
  code: z.string().trim().min(1, 'Indique o código.').max(20, 'O código tem no máximo 20 caracteres.'),
  name: z.string().trim().min(1, 'Indique o nome.').max(120, 'O nome tem no máximo 120 caracteres.'),
  timezone: z
    .string()
    .trim()
    .min(1, 'Indique o fuso horário.')
    .max(64, 'O fuso horário tem no máximo 64 caracteres.')
    .refine(isTimeZone, 'Fuso horário desconhecido (ex.: Africa/Maputo).'),
  /** Optional on create (the server's default); an edit always sends one. */
  businessDayCutoff: z.union([z.literal(''), z.string().regex(HH_MM, 'Indique a hora no formato hh:mm.')]),
  active: z.boolean(),
});

export type StoreValues = z.infer<typeof storeSchema>;

/** An edit must keep a cutoff: `StoreUpdateRequest.businessDayCutoff` is required. */
export const storeUpdateSchema = storeSchema.extend({
  businessDayCutoff: z.string().regex(HH_MM, 'Indique a hora no formato hh:mm.'),
});
