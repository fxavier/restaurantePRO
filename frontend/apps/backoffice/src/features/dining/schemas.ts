import { z } from 'zod';

const code = z.string().trim().min(1, 'Indique o código.').max(20, 'O código tem no máximo 20 caracteres.');

/** "Nova área" / "Editar área": the contract's limits (`RoomAreaCreateRequest`, `RoomAreaUpdateRequest`). */
export const areaSchema = z.object({
  code,
  name: z.string().trim().min(1, 'Indique o nome.').max(120, 'O nome tem no máximo 120 caracteres.'),
  active: z.boolean(),
});

export type AreaValues = z.infer<typeof areaSchema>;

/**
 * A whole number typed in a text field, within the contract's bounds; the message says what is expected. It stays
 * text in the form (the shared form fields take one value type); the caller sends `Number(value)`.
 */
export function wholeNumber(min: number, max: number, message: string) {
  return z
    .string()
    .trim()
    .regex(/^\d{1,9}$/, message)
    .refine((value) => Number(value) >= min && Number(value) <= max, message);
}

/** "Nova mesa" / "Editar mesa": `seats` is a positive int32 (`exclusiveMinimum: 0`). */
export const tableSchema = z.object({
  code,
  seats: wholeNumber(1, 999_999_999, 'Indique um número inteiro de lugares, pelo menos 1.'),
  areaId: z.string().min(1, 'Escolha a área.'),
  active: z.boolean(),
});

export type TableValues = z.infer<typeof tableSchema>;

/** "Reservas: definições" (`DiningSettingsRequest`): window 1–1440 min, grace 0–1440 min. */
export const settingsSchema = z.object({
  reservationWindowMinutes: wholeNumber(1, 1440, 'Indique um número inteiro de minutos, de 1 a 1440.'),
  noShowGraceMinutes: wholeNumber(0, 1440, 'Indique um número inteiro de minutos, de 0 a 1440.'),
});

export type SettingsValues = z.infer<typeof settingsSchema>;
