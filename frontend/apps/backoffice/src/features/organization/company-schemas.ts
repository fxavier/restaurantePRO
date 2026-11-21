import { z } from 'zod';
import type { CompanyProfileView } from './store-types';

/** "Editar perfil da empresa": the contract's limits (`CompanyProfileUpdateRequest`), in Portuguese. */
export const companyProfileSchema = z.object({
  name: z.string().trim().min(1, 'Indique o nome legal.').max(200, 'O nome tem no máximo 200 caracteres.'),
  taxId: z.string().trim().min(1, 'Indique o número fiscal.').max(50, 'O número fiscal tem no máximo 50 caracteres.'),
  country: z
    .string()
    .trim()
    .regex(/^[A-Za-z]{2}$/, 'Indique o código do país com 2 letras (ex.: MZ).')
    .toUpperCase(),
  currency: z
    .string()
    .trim()
    .regex(/^[A-Za-z]{3}$/, 'Indique o código da moeda com 3 letras (ex.: MZN).')
    .toUpperCase(),
});

export type CompanyProfileValues = z.infer<typeof companyProfileSchema>;

/** "Editar perfil da empresa" starts from the profile as read. */
export function companyProfileFormValues({ name, taxId, country, currency }: CompanyProfileView): CompanyProfileValues {
  return { name: name ?? '', taxId: taxId ?? '', country: country ?? '', currency: currency ?? '' };
}
