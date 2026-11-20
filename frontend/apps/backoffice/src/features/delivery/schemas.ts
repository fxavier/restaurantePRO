import { z } from 'zod';

/**
 * A delivery zone's form: the contract's limits (`ZoneCreateRequest`, `ZoneUpdateRequest`) with Portuguese messages.
 * `feeVariantId` is '' for "no variant"; the code is fixed once the zone exists (the update has none).
 */
export const zoneSchema = z.object({
  // Shown in capitals, so sent in capitals: "z1" and "Z1" would otherwise be two zones of the store.
  code: z.string().trim().min(1, 'Indique o código.').max(20, 'O código tem no máximo 20 caracteres.').toUpperCase(),
  name: z.string().trim().min(1, 'Indique o nome.').max(80, 'O nome tem no máximo 80 caracteres.'),
  feeItemId: z.string().min(1, 'Escolha o artigo de taxa.'),
  feeVariantId: z.string(),
  active: z.boolean(),
});

export type ZoneValues = z.infer<typeof zoneSchema>;
