import { z } from 'zod';
import type { CodePage, PrinterMode } from './types';

/**
 * The contract marks every field of the production views optional (openapi.json), while the backend always fills the
 * ones below. They are parsed once, where the answer comes in, so the screen never handles a printer without a mode.
 */
export const PRINTER_MODES = ['NORMAL', 'HOLD', 'IGNORE', 'REDIRECT'] as const satisfies readonly PrinterMode[];
export const CODE_PAGES = ['CP437', 'CP850', 'CP860', 'WPC1252', 'CP858'] as const satisfies readonly CodePage[];

const printer = z.object({
  id: z.string(),
  code: z.string(),
  name: z.string(),
  host: z.string(),
  port: z.number(),
  codePage: z.enum(CODE_PAGES),
  active: z.boolean(),
  mode: z.enum(PRINTER_MODES),
  redirectPrinterId: z.string().nullish(),
  version: z.number(),
  lastStatus: z.string().nullish(),
  lastStatusAt: z.string().nullish(),
});

export type Printer = z.infer<typeof printer>;

export function parsePrinters(views: unknown[]): Printer[] {
  return views.map((view) => printer.parse(view));
}
