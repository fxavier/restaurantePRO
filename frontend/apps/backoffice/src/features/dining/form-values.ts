import type { SettingsValues, TableValues } from './schemas';
import type { DiningSettingsView, RestaurantTableSummary } from './types';

/** "Editar mesa" starts from the table as read; numbers are text in the form (see `wholeNumber`). */
export function tableFormValues({ code, seats, areaId, active }: RestaurantTableSummary): TableValues {
  return { code: code ?? '', seats: String(seats ?? ''), areaId: areaId ?? '', active: active ?? true };
}

/** "Reservas: definições" starts from the settings as read. */
export function settingsFormValues({ reservationWindowMinutes, noShowGraceMinutes }: DiningSettingsView): SettingsValues {
  return { reservationWindowMinutes: String(reservationWindowMinutes ?? ''), noShowGraceMinutes: String(noShowGraceMinutes ?? '') };
}
