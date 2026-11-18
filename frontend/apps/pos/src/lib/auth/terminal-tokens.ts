import { signClientAssertion } from './client-assertion';
import type { Enrolment } from './device-store';

/**
 * - `idle`: no enrolled device yet (or stopped).
 * - `ready`: a token is held.
 * - `unavailable`: Keycloak did not answer usefully; retried every few seconds ("Sem ligação", FC §11).
 * - `revoked`: Keycloak refused the client (disabled or deleted, D182, or a skewed clock): no more requests; the key
 *   is kept, and only the user's confirmed "Registar de novo" erases it.
 */
export type TokenStatus = 'idle' | 'ready' | 'unavailable' | 'revoked';

export type TerminalTokens = {
  /** Gets a first token for an enrolled device and keeps it fresh from then on. */
  start(enrolment: Enrolment, privateKey: CryptoKey): Promise<void>;
  stop(): void;
  /** True between start and stop: API calls then need the terminal bearer. */
  active(): boolean;
  /** The current access token, read synchronously by the POS middleware; memory only, never storage. */
  accessToken(): string | undefined;
  /** Renews first when there is no token or less than 60 s remain (a sleeping terminal misses its timer). */
  ensureFresh(): Promise<void>;
  /** Asks for a new token now; concurrent calls share one request. */
  renew(): Promise<void>;
  status(): TokenStatus;
  subscribe(listener: () => void): () => void;
};

type Options = {
  /** The devices realm's issuer (`VITE_DEVICES_ISSUER`); the token endpoint is below it, and it is the audience. */
  issuer: string;
  fetch?: typeof globalThis.fetch;
};

const RENEW_BEFORE_MS = 60_000;
const RETRY_MS = 5_000;
const ASSERTION_TYPE = 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer';
/** OAuth errors Keycloak answers for a disabled or deleted client: the terminal is revoked. */
const REVOKED_ERRORS = new Set(['invalid_client', 'unauthorized_client']);

/**
 * The terminal's devices-realm token (D182, FC §6): client credentials with an RFC 7523 assertion signed by the
 * device key. Never logs a token or an assertion.
 */
export function createTerminalTokens({ issuer, fetch = (...args) => globalThis.fetch(...args) }: Options): TerminalTokens {
  const tokenEndpoint = `${issuer}/protocol/openid-connect/token`;
  const listeners = new Set<() => void>();
  let device: { enrolment: Enrolment; privateKey: CryptoKey } | undefined;
  let token: string | undefined;
  let expiresAt = 0;
  let current: TokenStatus = 'idle';
  let timer: ReturnType<typeof setTimeout> | undefined;
  let inFlight: Promise<void> | undefined;

  function setStatus(next: TokenStatus) {
    if (next === current) return;
    current = next;
    for (const listener of listeners) listener();
  }

  function schedule(delay: number) {
    clearTimeout(timer);
    timer = setTimeout(() => void renew(), delay);
  }

  function renew(): Promise<void> {
    inFlight ??= request().finally(() => {
      inFlight = undefined;
    });
    return inFlight;
  }

  async function request() {
    const asking = device;
    if (!asking) return;
    const asked = Date.now();
    const outcome = await ask(asking, asked);
    if (device !== asking) return; // stopped meanwhile
    if (outcome.kind === 'token') {
      token = outcome.accessToken;
      expiresAt = asked + outcome.expiresIn * 1000;
      // Renew when 60 s remain; a token shorter than two minutes is renewed at half its life instead.
      schedule(Math.max(outcome.expiresIn * 1000 - RENEW_BEFORE_MS, (outcome.expiresIn * 1000) / 2));
      setStatus('ready');
    } else if (outcome.kind === 'revoked') {
      clearTimeout(timer);
      device = undefined;
      token = undefined;
      setStatus('revoked');
    } else {
      if (Date.now() >= expiresAt) token = undefined;
      schedule(RETRY_MS);
      setStatus('unavailable');
    }
  }

  type Outcome = { kind: 'token'; accessToken: string; expiresIn: number } | { kind: 'revoked' } | { kind: 'unavailable' };

  async function ask({ enrolment, privateKey }: NonNullable<typeof device>, now: number): Promise<Outcome> {
    try {
      const assertion = await signClientAssertion({ clientId: enrolment.clientId, audience: issuer, privateKey, now });
      const response = await fetch(tokenEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'client_credentials',
          client_id: enrolment.clientId,
          client_assertion_type: ASSERTION_TYPE,
          client_assertion: assertion,
        }),
        credentials: 'omit',
      });
      const body: unknown = await response.json().catch(() => undefined);
      if (response.ok && isTokenAnswer(body)) {
        return { kind: 'token', accessToken: body.access_token, expiresIn: body.expires_in };
      }
      if ((response.status === 400 || response.status === 401) && isRevocation(body)) return { kind: 'revoked' };
      return { kind: 'unavailable' };
    } catch {
      return { kind: 'unavailable' };
    }
  }

  return {
    async start(enrolment, privateKey) {
      device = { enrolment, privateKey };
      token = undefined;
      expiresAt = 0;
      await renew();
    },
    stop() {
      clearTimeout(timer);
      device = undefined;
      token = undefined;
      expiresAt = 0;
      setStatus('idle');
    },
    active: () => device !== undefined,
    accessToken: () => token,
    async ensureFresh() {
      if (device && (!token || expiresAt - Date.now() < RENEW_BEFORE_MS)) await renew();
    },
    renew,
    status: () => current,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

function isTokenAnswer(body: unknown): body is { access_token: string; expires_in: number } {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as Record<string, unknown>).access_token === 'string' &&
    typeof (body as Record<string, unknown>).expires_in === 'number'
  );
}

function isRevocation(body: unknown): boolean {
  const error = typeof body === 'object' && body !== null ? (body as Record<string, unknown>).error : undefined;
  return typeof error === 'string' && REVOKED_ERRORS.has(error);
}
