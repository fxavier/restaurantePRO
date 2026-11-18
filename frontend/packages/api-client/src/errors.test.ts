import { describe, expect, it } from 'vitest';
import { ApiError, createApiClient } from './index';

const BASE = 'http://api.test';

function respondWith(response: Response | (() => never)) {
  return async () => (typeof response === 'function' ? response() : response);
}

function problem(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

async function failureOf(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the call to fail');
}

describe('ApiError mapping', () => {
  it('maps a problem+json answer to status, code, title and detail', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(
        problem(409, {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: 'The order changed.',
          code: 'ORDER_VERSION_CONFLICT',
        }),
      ),
    });

    const error = await failureOf(client.GET('/api/v1/auth/session'));

    expect(error).toMatchObject({
      status: 409,
      code: 'ORDER_VERSION_CONFLICT',
      title: 'Conflict',
      detail: 'The order changed.',
    });
    expect(error.errors).toBeUndefined();
  });

  it('keeps the validation errors[] of a 400', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(
        problem(400, {
          title: 'Bad Request',
          status: 400,
          detail: 'Invalid request content.',
          errors: [{ field: 'name', message: 'must not be blank' }],
        }),
      ),
    });

    const error = await failureOf(client.GET('/api/v1/auth/session'));

    expect(error.status).toBe(400);
    expect(error.code).toBe('HTTP_400');
    expect(error.errors).toEqual([{ field: 'name', message: 'must not be blank' }]);
  });

  it('keeps the step-up fields of a 401 MFA_STEP_UP_REQUIRED', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(
        problem(401, {
          title: 'Unauthorized',
          status: 401,
          detail: 'A fresh second factor is required.',
          code: 'MFA_STEP_UP_REQUIRED',
          acrValues: 'mfa',
          maxAge: 300,
        }),
      ),
    });

    const error = await failureOf(client.GET('/api/v1/auth/session'));

    expect(error).toMatchObject({
      status: 401,
      code: 'MFA_STEP_UP_REQUIRED',
      acrValues: 'mfa',
      maxAge: 300,
    });
  });

  it('reads Retry-After in seconds on a 429', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(
        new Response(JSON.stringify({ title: 'Too Many Requests', status: 429, code: 'ENROLMENT_THROTTLED' }), {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '42' },
        }),
      ),
    });

    const error = await failureOf(client.GET('/api/v1/auth/session'));

    expect(error).toMatchObject({ status: 429, code: 'ENROLMENT_THROTTLED', retryAfter: 42 });
  });

  it('reads an HTTP-date Retry-After as the seconds left; one already past counts as absent', async () => {
    const inTwoMinutes = new Date(Date.now() + 120_000).toUTCString();
    const past = new Date(Date.now() - 60_000).toUTCString();
    const answer = (value: string) =>
      new Response(null, { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': value } });

    const future = await failureOf(createApiClient({ baseUrl: BASE, fetch: respondWith(answer(inTwoMinutes)) })
      .GET('/api/v1/auth/session'));
    const elapsed = await failureOf(createApiClient({ baseUrl: BASE, fetch: respondWith(answer(past)) })
      .GET('/api/v1/auth/session'));

    expect(future.retryAfter).toBeGreaterThan(115);
    expect(future.retryAfter).toBeLessThanOrEqual(120);
    expect(elapsed.retryAfter).toBeUndefined();
  });

  it('leaves retryAfter undefined when the header is absent or unreadable', async () => {
    const absent = await failureOf(createApiClient({ baseUrl: BASE, fetch: respondWith(problem(429, { title: 'x' })) })
      .GET('/api/v1/auth/session'));
    const garbage = await failureOf(createApiClient({ baseUrl: BASE,
      fetch: respondWith(new Response(null, { status: 429, headers: { 'Retry-After': 'soon' } })),
    }).GET('/api/v1/auth/session'));

    const zero = await failureOf(createApiClient({ baseUrl: BASE,
      fetch: respondWith(new Response(null, { status: 429, headers: { 'Retry-After': '0' } })),
    }).GET('/api/v1/auth/session'));

    expect(absent.retryAfter).toBeUndefined();
    expect(garbage.retryAfter).toBeUndefined();
    expect(zero.retryAfter).toBeUndefined();
  });

  it('maps an answer without a problem body to HTTP_<status> and the status text', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' })),
    });

    const error = await failureOf(client.GET('/api/v1/auth/session'));

    expect(error).toMatchObject({ status: 502, code: 'HTTP_502', title: 'Bad Gateway' });
  });

  it('maps a network failure to status 0 and code NETWORK', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(() => {
        throw new TypeError('Failed to fetch');
      }),
    });

    const error = await failureOf(client.GET('/api/v1/auth/session'));

    expect(error).toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('lets an abort through unchanged, so a cancelled query is not shown as "Sem ligação"', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(() => {
        throw new DOMException('The operation was aborted.', 'AbortError');
      }),
    });

    await expect(client.GET('/api/v1/auth/session')).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('returns the data of a successful answer', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: respondWith(Response.json({ name: 'Ana' })),
    });

    const { data } = await client.GET('/api/v1/auth/session');

    expect(data).toEqual({ name: 'Ana' });
  });
});
