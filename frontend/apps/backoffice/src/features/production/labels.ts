import type { StatusMap } from '@restaurantpro/ui';
import { EyeOff, Forward, Pause, Printer } from 'lucide-react';
import type { PrinterMode } from './types';

/** `PrinterMode` (CONTEXT: Normal / Espera / Ignorar / Redirecionar), by label, icon and colour together. */
export const printerModes: StatusMap<PrinterMode> = {
  NORMAL: { label: 'Normal', icon: Printer, tone: 'success' },
  HOLD: { label: 'Em espera', icon: Pause, tone: 'warning' },
  IGNORE: { label: 'Ignorar', icon: EyeOff, tone: 'destructive' },
  REDIRECT: { label: 'Redirecionar', icon: Forward, tone: 'info' },
};
