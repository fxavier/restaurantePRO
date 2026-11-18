import type { Middleware } from 'openapi-fetch';

export type PosCredentials = {
  /** The terminal's access token from the devices realm, held in memory. */
  accessToken: () => string | undefined;
  /** The employee's POS session from the PIN login, held in memory; absent before login. */
  posSession: () => string | undefined;
};

/** POS (D182, FC §6): the terminal bearer and `X-Pos-Session` on every call, read fresh each time; never cookies. */
export function posMiddleware(credentials: PosCredentials): Middleware {
  return {
    onRequest({ request }) {
      const headers = new Headers(request.headers);
      const token = credentials.accessToken();
      const session = credentials.posSession();
      if (token) headers.set('Authorization', `Bearer ${token}`);
      if (session) headers.set('X-Pos-Session', session);
      return new Request(request, { headers, credentials: 'omit' });
    },
  };
}

const CSRF_COOKIE = '__Host-XSRF-TOKEN';
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export type BackOfficeOptions = {
  /** The tenant this tab displays (`SessionView.tenantId`), read fresh on every write; absent before the session loads. */
  expectedTenant: () => string | undefined;
  /** The document's cookies; injectable for tests. */
  cookies?: () => string;
};

/**
 * BackOffice (D184, FC §6): the BFF session cookie on every call, and on every unsafe one the CSRF header and
 * `X-Expected-Tenant`, so the BFF refuses (409 `TENANT_CHANGED`) a write after another tab switched company (FE-F16).
 */
export function backOfficeMiddleware({ expectedTenant, cookies = () => document.cookie }: BackOfficeOptions): Middleware {
  return {
    onRequest({ request }) {
      const headers = new Headers(request.headers);
      if (UNSAFE_METHODS.has(request.method)) {
        const token = readCookie(cookies(), CSRF_COOKIE);
        if (token) headers.set('X-XSRF-TOKEN', token);
        const tenant = expectedTenant();
        if (tenant) headers.set('X-Expected-Tenant', tenant);
      }
      return new Request(request, { headers, credentials: 'include' });
    },
  };
}

export function readCookie(cookieHeader: string, name: string): string | undefined {
  for (const pair of cookieHeader.split(';')) {
    const separator = pair.indexOf('=');
    if (separator < 0) continue;
    if (pair.slice(0, separator).trim() === name) return decodeURIComponent(pair.slice(separator + 1).trim());
  }
  return undefined;
}
