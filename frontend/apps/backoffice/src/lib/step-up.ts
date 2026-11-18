import { ApiError } from '@restaurantpro/api-client';
import { z } from 'zod';

/** Where the BFF brings the browser back after a step-up started for a pending command (FC §4, §6). */
export const RESUME_PATH = '/step-up/resume';

const STORAGE_KEY = 'restaurantpro.backoffice.pendingCommand';
/** A command older than this is not resumed: the user has moved on, and the data may have changed meanwhile. */
const MAX_AGE_MS = 15 * 60_000;

export function isStepUpRequired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401 && error.code === 'MFA_STEP_UP_REQUIRED';
}

/** A path of this origin: what the BFF accepts as `return`, checked again on the way back. */
export function isAppPath(path: string): boolean {
  // eslint-disable-next-line no-control-regex
  return path.startsWith('/') && !path.startsWith('//') && !path.includes('\\') && !/[\u0000-\u001f\u007f]/.test(path);
}

/**
 * The command a step-up interrupted (FC §6), kept in sessionStorage while the browser is at Keycloak. It never holds a
 * secret: a command whose request carries one is not resumable, and a command whose answer is one (the enrolment
 * code) is resumed by repeating the request. `idempotencyKey` is null where the contract takes none.
 */
const pendingSchema = z.object({
  method: z.enum(['POST', 'PUT', 'PATCH', 'DELETE']),
  path: z.string().startsWith('/api/'),
  body: z.unknown(),
  idempotencyKey: z.uuid().nullable(),
  returnTo: z.string().refine(isAppPath),
  /** What the resume page names, e.g. the terminal's name; never a secret. */
  label: z.string().max(200),
  /** The company the command was meant for; a command of another company is never sent. */
  tenantId: z.string().min(1),
  savedAt: z.number(),
});

export type PendingCommand = z.infer<typeof pendingSchema>;

export function savePendingCommand(command: Omit<PendingCommand, 'savedAt'>) {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ ...command, savedAt: Date.now() }));
}

/** The stored command if it is well formed, recent and of this company; anything else is discarded. */
export function readPendingCommand(tenantId: string | undefined): PendingCommand | undefined {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (raw === null) return undefined;
  const parsed = pendingSchema.safeParse(parseJson(raw));
  const now = Date.now();
  if (
    parsed.success &&
    parsed.data.tenantId === tenantId &&
    parsed.data.savedAt <= now + 60_000 &&
    now - parsed.data.savedAt < MAX_AGE_MS
  ) {
    return parsed.data;
  }
  clearPendingCommand();
  return undefined;
}

export function clearPendingCommand() {
  sessionStorage.removeItem(STORAGE_KEY);
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/**
 * The BFF's step-up entry (bff/README) with the challenge's level and maximum age, and the path to come back to. A
 * challenge without them asks for the D184 defaults.
 */
export function stepUpUrl(error: ApiError, returnPath: string): string {
  const acrValues = error.acrValues === '1' ? '1' : '2';
  const maxAge = Number.isInteger(error.maxAge) && error.maxAge! >= 0 ? error.maxAge! : 300;
  const query = new URLSearchParams({ acr_values: acrValues, max_age: String(maxAge), return: returnPath });
  return `/bff/step-up?${query}`;
}
