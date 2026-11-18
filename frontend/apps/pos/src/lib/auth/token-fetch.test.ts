import { describe, expect, it, vi } from 'vitest';
import type { TerminalTokens } from './terminal-tokens';
import { withTerminalToken } from './token-fetch';

const URL_ = 'http://pos.test/api/v1/pos/tables';

/** Tokens whose renewals hand out the next value of a list. */
function fakeTokens(values: (string | undefined)[], active = true) {
  let token = values.shift();
  const tokens = {
    active: () => active,
    accessToken: () => token,
    ensureFresh: vi.fn(async () => {}),
    renew: vi.fn(async () => {
      token = values.shift();
    }),
  } satisfies Partial<TerminalTokens>;
  return tokens as typeof tokens & TerminalTokens;
}

function server(...answers: Response[]) {
  const seen: Request[] = [];
  const fetch = vi.fn(async (request: Request) => {
    seen.push(request);
    return answers.shift()!;
  });
  return { fetch, seen };
}

const unauthorized = (code?: string) =>
  Response.json({ title: 'Unauthorized', status: 401, ...(code ? { code } : {}) }, { status: 401 });

const bearerOf = (request?: Request) => request?.headers.get('Authorization');

describe('withTerminalToken', () => {
  it('makes sure the token is fresh and sends the current one', async () => {
    const tokens = fakeTokens(['jwt-1']);
    const { fetch, seen } = server(Response.json({}));

    await withTerminalToken(tokens, fetch)(new Request(URL_, { headers: { Authorization: 'Bearer stale' } }));

    expect(tokens.ensureFresh).toHaveBeenCalled();
    expect(bearerOf(seen[0])).toBe('Bearer jwt-1');
  });

  it('renews once on a 401 and repeats the request, body included, with the new token', async () => {
    const tokens = fakeTokens(['jwt-1', 'jwt-2']);
    const { fetch, seen } = server(unauthorized(), Response.json({ ok: true }));

    const response = await withTerminalToken(tokens, fetch)(
      new Request(URL_, { method: 'POST', body: JSON.stringify({ a: 1 }), headers: { 'Content-Type': 'application/json' } }),
    );

    expect(response.status).toBe(200);
    expect(tokens.renew).toHaveBeenCalledTimes(1);
    expect(seen.map(bearerOf)).toEqual(['Bearer jwt-1', 'Bearer jwt-2']);
    await expect(seen[1]!.json()).resolves.toEqual({ a: 1 });
  });

  it('answers the second 401 as it is: renewal happens once', async () => {
    const tokens = fakeTokens(['jwt-1', 'jwt-2', 'jwt-3']);
    const { fetch } = server(unauthorized(), unauthorized());

    const response = await withTerminalToken(tokens, fetch)(new Request(URL_));

    expect(response.status).toBe(401);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(tokens.renew).toHaveBeenCalledTimes(1);
  });

  it('does not renew on POS_SESSION_INVALID: that is the employee session, not the terminal', async () => {
    const tokens = fakeTokens(['jwt-1', 'jwt-2']);
    const { fetch } = server(unauthorized('POS_SESSION_INVALID'));

    const response = await withTerminalToken(tokens, fetch)(new Request(URL_));

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({ code: 'POS_SESSION_INVALID' });
    expect(tokens.renew).not.toHaveBeenCalled();
  });

  it('answers the 401 when renewal yields no token (revoked or unreachable)', async () => {
    const tokens = fakeTokens(['jwt-1', undefined]);
    const { fetch } = server(unauthorized());

    const response = await withTerminalToken(tokens, fetch)(new Request(URL_));

    expect(response.status).toBe(401);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('sends nothing without a token (Keycloak unreachable): the call fails like a lost connection', async () => {
    const tokens = fakeTokens([undefined]);
    const { fetch } = server();

    await expect(withTerminalToken(tokens, fetch)(new Request(URL_))).rejects.toThrow(TypeError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('passes requests through untouched before enrolment', async () => {
    const tokens = fakeTokens([undefined], false);
    const { fetch, seen } = server(unauthorized());

    await withTerminalToken(tokens, fetch)(new Request(URL_));

    expect(tokens.ensureFresh).not.toHaveBeenCalled();
    expect(tokens.renew).not.toHaveBeenCalled();
    expect(seen[0]?.headers.has('Authorization')).toBe(false);
  });
});
