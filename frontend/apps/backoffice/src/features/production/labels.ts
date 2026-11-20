import type { StatusMap } from '@restaurantpro/ui';
import { CircleCheck, CircleHelp, CircleX, Clock, EyeOff, Forward, Loader, Pause, Printer, RotateCw } from 'lucide-react';
import type { PrinterMode, PrintJobStatus } from './types';

/** `PrinterMode` (CONTEXT: Normal / Espera / Ignorar / Redirecionar), by label, icon and colour together. */
export const printerModes: StatusMap<PrinterMode> = {
  NORMAL: { label: 'Normal', icon: Printer, tone: 'success' },
  HOLD: { label: 'Em espera', icon: Pause, tone: 'warning' },
  IGNORE: { label: 'Ignorar', icon: EyeOff, tone: 'destructive' },
  REDIRECT: { label: 'Redirecionar', icon: Forward, tone: 'info' },
};

/**
 * `PrintJobStatus` (§8.3). SENT means the bytes were written, not that paper came out. UNKNOWN is an outcome nobody
 * knows yet: "Por confirmar", never a failure.
 */
export const jobStatuses: StatusMap<PrintJobStatus> = {
  QUEUED: { label: 'Em fila', icon: Clock, tone: 'neutral' },
  PROCESSING: { label: 'A enviar', icon: Loader, tone: 'primary' },
  SENT: { label: 'Enviado', icon: CircleCheck, tone: 'success' },
  RETRY_WAIT: { label: 'Nova tentativa em breve', icon: RotateCw, tone: 'warning' },
  FAILED: { label: 'Falhou', icon: CircleX, tone: 'destructive' },
  UNKNOWN: { label: 'Por confirmar', icon: CircleHelp, tone: 'info' },
};

/** The queue's state filter, as the server filters it (`status`); empty is every state. */
export const jobFilters: { value: PrintJobStatus | ''; label: string }[] = [
  { value: '', label: 'Todos' },
  { value: 'FAILED', label: 'Falhados' },
  { value: 'UNKNOWN', label: 'Por confirmar' },
  { value: 'RETRY_WAIT', label: 'Nova tentativa em breve' },
  { value: 'QUEUED', label: 'Em fila' },
  { value: 'SENT', label: 'Enviados' },
];

const sources: Record<string, string> = {
  BATCH: 'Pedido',
  VOID: 'Anulação',
  SUBTOTAL: 'Consulta de mesa',
  DOCUMENT: 'Documento',
  TEST: 'Página de teste',
  RECEIPT_SLIP: 'Talão de pagamento',
  DELIVERY_SLIP: 'Guia de entrega',
  DRAWER_KICK: 'Abertura de gaveta',
};

/** What a ticket or job prints (`sourceType`) and where (`destination`), e.g. "Pedido · COZINHA". */
export function jobTitle(sourceType: string, destination: string | null | undefined): string {
  const source = sources[sourceType] ?? sourceType;
  return destination ? `${source} · ${destination}` : source;
}

/** How a FAILED or UNKNOWN job was resolved. */
export const resolutions: Record<string, string> = {
  RETRIED: 'Reenviado',
  CONFIRMED_PRINTED: 'Confirmado como impresso',
};
