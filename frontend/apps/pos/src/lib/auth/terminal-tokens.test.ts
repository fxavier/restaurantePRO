import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { generateDeviceKey } from './client-assertion';
import { createTerminalTokens } from './terminal-tokens';

const ISSUER = 'https://keycloak.test/realms/restaurantpro-devices';
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`;
const ENROLMENT = {
  id: '6f1c2a9e-0000-4000-8000-000000000001',
  storeId: '6f1c2a9e-0000-4000-8000-0000000000aa',
  clientId: 'pos-6f1c2a9e-0000-4000-8000-000000000001',
};

type Answer = Response | (() => never);

/** A fake token endpoint answering from a queue; the last answer repeats. */
function tokenEndpoint(...answers: Answer[]) {
  const requests: { url: string; form: URLSearchParams }[] = [];
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push({ url: String(input), form: new URLSearchParams(String(init?.body)) });
    const answer = answers.length > 1 ? answers.shift()! : answers[0]!;
    if (typeof answer === 'function') answer();
    return (answer as Response).clone();
  });
  return { fetch, requests };
}

const token = (value: string, expiresIn = 300) =>
  Response.json({ access_token: value, expires_in: expiresIn, token_type: 'Bearer' });
const refused = (status: number, error: string) => Response.json({ error, error_description: 'x' }, { status });
const unreachable = () => {
  throw new TypeError('Failed to fetch');
};

function claimsOf(assertion: string) {
  const part = assertion.split('.')[1]!.replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part.padEnd(part.length + ((4 - (part.length % 4)) % 4), '='))) as Record<string, unknown>;
}

describe('terminal tokens', () => {
  let keyPair: CryptoKeyPair;

  beforeEach(async () => {
    keyPair = await generateDeviceKey();
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('asks the devices realm for client credentials with a signed assertion, and keeps the token in memory', async () => {
    const endpoint = tokenEndpoint(token('jwt-1'));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });

    await tokens.start(ENROLMENT, keyPair.privateKey);

    expect(tokens.status()).toBe('ready');
    expect(tokens.accessToken()).toBe('jwt-1');
    const [request] = endpoint.requests;
    expect(request?.url).toBe(TOKEN_ENDPOINT);
    expect(request?.form.get('grant_type')).toBe('client_credentials');
    expect(request?.form.get('client_id')).toBe(ENROLMENT.clientId);
    expect(request?.form.get('client_assertion_type')).toBe('urn:ietf:params:oauth:client-assertion-type:jwt-bearer');
    expect(claimsOf(request!.form.get('client_assertion')!)).toMatchObject({
      iss: ENROLMENT.clientId,
      sub: ENROLMENT.clientId,
      aud: ISSUER,
    });
  });

  it('renews when less than 60 s remain, before the token expires', async () => {
    const endpoint = tokenEndpoint(token('jwt-1', 300), token('jwt-2', 300));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });
    await tokens.start(ENROLMENT, keyPair.privateKey);

    await vi.advanceTimersByTimeAsync(239_000);
    expect(endpoint.fetch).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1_000);
    // The signature is real WebCrypto work, not a timer: wait for it.
    await vi.waitFor(() => expect(tokens.accessToken()).toBe('jwt-2'));
    expect(endpoint.fetch).toHaveBeenCalledTimes(2);
    tokens.stop();
  });

  it('renews before a request when the scheduled renewal is late (the terminal slept)', async () => {
    const endpoint = tokenEndpoint(token('jwt-1', 300), token('jwt-2', 300));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });
    await tokens.start(ENROLMENT, keyPair.privateKey);
    vi.setSystemTime(Date.now() + 290_000); // the clock moved, the timer did not fire

    await tokens.ensureFresh();

    expect(tokens.accessToken()).toBe('jwt-2');
    tokens.stop();
  });

  it('sends one token request for concurrent renewals', async () => {
    const endpoint = tokenEndpoint(token('jwt-1'), token('jwt-2'));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });
    await tokens.start(ENROLMENT, keyPair.privateKey);

    await Promise.all([tokens.renew(), tokens.renew(), tokens.renew()]);

    expect(endpoint.fetch).toHaveBeenCalledTimes(2);
    tokens.stop();
  });

  it.each([
    ['unreachable', unreachable],
    ['a 503', () => new Response('down', { status: 503 })],
  ])('is unavailable when Keycloak is %s, and tries again every 5 s until it answers', async (_case, failure) => {
    const endpoint = tokenEndpoint(failure as unknown as Answer, token('jwt-1'));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });
    const statuses: string[] = [];
    tokens.subscribe(() => statuses.push(tokens.status()));

    await tokens.start(ENROLMENT, keyPair.privateKey);
    expect(tokens.status()).toBe('unavailable');
    expect(tokens.accessToken()).toBeUndefined();

    await vi.advanceTimersByTimeAsync(5_000);

    await vi.waitFor(() => expect(tokens.status()).toBe('ready'));
    expect(statuses).toEqual(['unavailable', 'ready']);
    tokens.stop();
  });

  it('gives up on a token request after 10 s without an answer: "Sem ligação", and calls stop waiting', async () => {
    let calledAt = 0;
    const silent = vi.fn(
      (_input: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_, reject) => {
          calledAt = Date.now();
          init?.signal?.addEventListener('abort', () => reject(init.signal!.reason), { once: true });
        }),
    );
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: silent });
    let started = false;
    void tokens.start(ENROLMENT, keyPair.privateKey).then(() => (started = true));
    await vi.waitFor(() => expect(silent).toHaveBeenCalledTimes(1));

    // vi.waitFor moves fake time while it polls: count the 10 s from the call itself.
    await vi.advanceTimersByTimeAsync(9_999 - (Date.now() - calledAt));
    expect(started).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    await vi.waitFor(() => expect(started).toBe(true));
    expect(tokens.status()).toBe('unavailable');
    expect(silent.mock.calls[0]?.[1]?.signal).toBeDefined();
    tokens.stop();
  });

  it.each([
    [401, 'invalid_client'],
    [400, 'unauthorized_client'],
  ])('is revoked when Keycloak refuses the client (%s %s): no token, no further request', async (status, error) => {
    const endpoint = tokenEndpoint(token('jwt-1', 300), refused(status, error));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });
    await tokens.start(ENROLMENT, keyPair.privateKey);

    await tokens.renew();

    expect(tokens.status()).toBe('revoked');
    expect(tokens.accessToken()).toBeUndefined();
    await vi.advanceTimersByTimeAsync(600_000);
    expect(endpoint.fetch).toHaveBeenCalledTimes(2);
  });

  it('keeps nothing in localStorage or sessionStorage', async () => {
    const endpoint = tokenEndpoint(token('jwt-1'));
    const tokens = createTerminalTokens({ issuer: ISSUER, fetch: endpoint.fetch });

    await tokens.start(ENROLMENT, keyPair.privateKey);

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    tokens.stop();
  });
});
