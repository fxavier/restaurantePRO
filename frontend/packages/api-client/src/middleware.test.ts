import { describe, expect, it } from 'vitest';
import { type ApiClient, backOfficeMiddleware, createApiClient, posMiddleware, readCookie } from './index';

const BASE = 'http://api.test';
const AREA = '00000000-0000-0000-0000-000000000001';
const TENANT = '0193a1b2-0000-7000-8000-00000000000a';

function capturingFetch() {
  const requests: Request[] = [];
  const fetch = async (request: Request) => {
    requests.push(request);
    return Response.json({});
  };
  return { fetch, requests };
}

describe('POS middleware', () => {
  it('sends the terminal bearer and the POS session from the in-memory providers', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE,
      fetch,
      middleware: [posMiddleware({ accessToken: () => 'terminal-jwt', posSession: () => 'session-1' })],
    });

    await client.GET('/api/v1/auth/session');

    expect(requests[0]?.headers.get('Authorization')).toBe('Bearer terminal-jwt');
    expect(requests[0]?.headers.get('X-Pos-Session')).toBe('session-1');
  });

  it('reads the providers on every request, so a renewed token is used at once', async () => {
    const { fetch, requests } = capturingFetch();
    let token = 'first';
    const client = createApiClient({ baseUrl: BASE,
      fetch,
      middleware: [posMiddleware({ accessToken: () => token, posSession: () => undefined })],
    });

    await client.GET('/api/v1/auth/session');
    token = 'second';
    await client.GET('/api/v1/auth/session');

    expect(requests.map((r) => r.headers.get('Authorization'))).toEqual(['Bearer first', 'Bearer second']);
  });

  it('omits X-Pos-Session before an employee has logged in', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE,
      fetch,
      middleware: [posMiddleware({ accessToken: () => 'terminal-jwt', posSession: () => undefined })],
    });

    await client.POST('/api/v1/auth/pos-login', { body: { employeeCode: '7', pin: 'x' } });

    expect(requests[0]?.headers.get('Authorization')).toBe('Bearer terminal-jwt');
    expect(requests[0]?.headers.has('X-Pos-Session')).toBe(false);
  });

  it('sends no cookies', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE,
      fetch,
      middleware: [posMiddleware({ accessToken: () => 't', posSession: () => 's' })],
    });

    await client.GET('/api/v1/auth/session');

    expect(requests[0]?.credentials).toBe('omit');
  });
});

describe('BackOffice middleware', () => {
  const cookies = '__Host-bff-session=ignored; __Host-XSRF-TOKEN=abc%3D123';
  const backOffice = backOfficeMiddleware({ cookies: () => cookies, expectedTenant: () => TENANT });

  const unsafeCalls = {
    POST: (client: ApiClient) =>
      client.POST('/api/v1/auth/pos-login', { body: { employeeCode: '7', pin: 'x' } }),
    PUT: (client: ApiClient) =>
      client.PUT('/api/v1/backoffice/areas/{id}', {
        params: { path: { id: AREA } },
        body: { active: true, code: 'S1', name: 'Sala', version: 0 },
      }),
    DELETE: (client: ApiClient) =>
      client.DELETE('/api/v1/backoffice/catalog/macros/{id}', {
        params: { path: { id: AREA }, query: { version: 0 } },
      }),
  };

  // The contract has no PATCH operation; the middleware treats PATCH as unsafe all the same.
  it.each(Object.entries(unsafeCalls))('sends X-XSRF-TOKEN from the cookie on %s', async (_method, call) => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE, fetch, middleware: [backOffice] });

    await call(client);

    expect(requests[0]?.headers.get('X-XSRF-TOKEN')).toBe('abc=123');
  });

  it('sends no CSRF header on a GET', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE, fetch, middleware: [backOffice] });

    await client.GET('/api/v1/auth/session');

    expect(requests[0]?.headers.has('X-XSRF-TOKEN')).toBe(false);
  });

  it('sends credentials on every request', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE, fetch, middleware: [backOffice] });

    await client.GET('/api/v1/auth/session');
    await client.POST('/api/v1/auth/pos-login', { body: { employeeCode: '7', pin: 'x' } });

    expect(requests.map((r) => r.credentials)).toEqual(['include', 'include']);
  });

  it.each(Object.entries(unsafeCalls))('sends X-Expected-Tenant from the provider on %s', async (_method, call) => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE, fetch, middleware: [backOffice] });

    await call(client);

    expect(requests[0]?.headers.get('X-Expected-Tenant')).toBe(TENANT);
  });

  it('sends X-Expected-Tenant on a read too, once the tab is pinned', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE, fetch, middleware: [backOffice] });

    await client.GET('/api/v1/auth/session');

    expect(requests[0]?.headers.get('X-Expected-Tenant')).toBe(TENANT);
  });

  it('reads the tenant on every request, so a write after a switch names the new tenant', async () => {
    const { fetch, requests } = capturingFetch();
    let tenant: string | undefined = undefined;
    const client = createApiClient({ baseUrl: BASE,
      fetch,
      middleware: [backOfficeMiddleware({ cookies: () => cookies, expectedTenant: () => tenant })],
    });

    await unsafeCalls.POST(client);
    tenant = TENANT;
    await unsafeCalls.POST(client);

    expect(requests.map((r) => r.headers.get('X-Expected-Tenant'))).toEqual([null, TENANT]);
  });

  describe('an answer relayed in another tenant than the expected one (FE-F16)', () => {
    const OTHER = '0193a1b2-0000-7000-8000-00000000000b';
    const answering = (relayedTenant?: string, status = 200) => async () =>
      Response.json({ stores: ['secret'] }, {
        status,
        headers: relayedTenant ? { 'X-Relayed-Tenant': relayedTenant } : {},
      });

    it.each([200, 404])('throws TENANT_CHANGED instead of the %s answer', async (status) => {
      const client = createApiClient({ baseUrl: BASE, fetch: answering(OTHER, status), middleware: [backOffice] });

      await expect(client.GET('/api/v1/auth/session')).rejects.toMatchObject({ status: 409, code: 'TENANT_CHANGED' });
    });

    it('passes an answer relayed in the expected tenant, or naming none', async () => {
      for (const relayed of [TENANT, undefined]) {
        const client = createApiClient({ baseUrl: BASE, fetch: answering(relayed), middleware: [backOffice] });

        await expect(client.GET('/api/v1/auth/session')).resolves.toMatchObject({ data: { stores: ['secret'] } });
      }
    });

    it('turns the backend refusing the expected tenant (403 TENANT_FORBIDDEN) into TENANT_CHANGED', async () => {
      const forbidden = async () =>
        Response.json({ status: 403, code: 'TENANT_FORBIDDEN', title: 'Forbidden' }, { status: 403 });
      const client = createApiClient({ baseUrl: BASE, fetch: forbidden, middleware: [backOffice] });

      await expect(client.GET('/api/v1/auth/session')).rejects.toMatchObject({ code: 'TENANT_CHANGED' });
    });

    it('leaves TENANT_FORBIDDEN alone before the tab has a tenant', async () => {
      const forbidden = async () =>
        Response.json({ status: 403, code: 'TENANT_FORBIDDEN', title: 'Forbidden' }, { status: 403 });
      const unpinned = backOfficeMiddleware({ cookies: () => cookies, expectedTenant: () => undefined });
      const client = createApiClient({ baseUrl: BASE, fetch: forbidden, middleware: [unpinned] });

      await expect(client.GET('/api/v1/auth/session')).rejects.toMatchObject({ code: 'TENANT_FORBIDDEN' });
    });

    it('checks nothing before the tab has a tenant', async () => {
      const unpinned = backOfficeMiddleware({ cookies: () => cookies, expectedTenant: () => undefined });
      const client = createApiClient({ baseUrl: BASE, fetch: answering(OTHER), middleware: [unpinned] });

      await expect(client.GET('/api/v1/auth/session')).resolves.toMatchObject({ data: { stores: ['secret'] } });
    });
  });

  it('sends no bearer token', async () => {
    const { fetch, requests } = capturingFetch();
    const client = createApiClient({ baseUrl: BASE, fetch, middleware: [backOffice] });

    await client.POST('/api/v1/auth/pos-login', { body: { employeeCode: '7', pin: 'x' } });

    expect(requests[0]?.headers.has('Authorization')).toBe(false);
  });
});

describe('readCookie', () => {
  it('finds a cookie by exact name and decodes it', () => {
    expect(readCookie('a=1; __Host-XSRF-TOKEN=x%2By; b=2', '__Host-XSRF-TOKEN')).toBe('x+y');
  });

  it('answers undefined when the cookie is absent', () => {
    expect(readCookie('XSRF-TOKEN=nope', '__Host-XSRF-TOKEN')).toBeUndefined();
  });
});
