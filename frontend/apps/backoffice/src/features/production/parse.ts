import { z } from 'zod';
import type { CodePage, PrinterMode, PrintJobStatus } from './types';

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

export const JOB_STATUSES = ['QUEUED', 'PROCESSING', 'SENT', 'RETRY_WAIT', 'FAILED', 'UNKNOWN'] as const satisfies readonly PrintJobStatus[];

const printJob = z.object({
  id: z.string(),
  printerId: z.string(),
  status: z.enum(JOB_STATUSES),
  sourceType: z.string(),
  kind: z.enum(['ORIGINAL', 'COPY']),
  destination: z.string().nullish(),
  attempts: z.number(),
  lastError: z.string().nullish(),
  resolution: z.enum(['CONFIRMED_PRINTED', 'RETRIED']).nullish(),
  createdAt: z.string(),
});

export type PrintJob = z.infer<typeof printJob>;

export function parseJobs(views: unknown[]): PrintJob[] {
  return views.map((view) => printJob.parse(view));
}

const ticket = z.object({
  id: z.string(),
  sourceType: z.string(),
  destination: z.string().nullish(),
  lines: z.array(z.string()).nullish(),
  createdAt: z.string(),
});

export type Ticket = z.infer<typeof ticket>;

export function parseTickets(views: unknown[]): Ticket[] {
  return views.map((view) => ticket.parse(view));
}

/**
 * What may still be done with a job (r2-decisions §4): a FAILED or UNKNOWN job not yet resolved is retried as a new
 * job; an UNKNOWN one may instead be acknowledged as printed. A drawer kick is never repeated (D158).
 */
export function canRetry(job: PrintJob): boolean {
  return (job.status === 'FAILED' || job.status === 'UNKNOWN') && !job.resolution && job.sourceType !== 'DRAWER_KICK';
}

export function canAcknowledge(job: PrintJob): boolean {
  return job.status === 'UNKNOWN' && !job.resolution;
}
