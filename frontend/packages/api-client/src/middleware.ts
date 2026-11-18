import type { Middleware } from 'openapi-fetch';
import { ApiError, errorFromResponse } from './errors';

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
  /** The tenant this tab's page was loaded in (its pin); absent before the first session read. */
  expectedTenant: () => string | undefined;
  /** The document's cookies; injectable for tests. */
  cookies?: () => string;
};

/**
 * BackOffice (D184, FC §6): the BFF session cookie on every call and the CSRF header on every unsafe one. Once the tab
 * is pinned, every call names its tenant in `X-Expected-Tenant` (FE-F16). The call throws `TENANT_CHANGED` instead of
 * handing over an answer when that tenant no longer holds: the BFF refused a write (409), it relayed the call in
 * another tenant (`X-Relayed-Tenant`), or the backend refused the expected tenant to the session's user (403
 * `TENANT_FORBIDDEN`: another user logged in from another tab).
 */
export function backOfficeMiddleware({ expectedTenant, cookies = () => document.cookie }: BackOfficeOptions): Middleware {
  return {
    onRequest({ request }) {
      const headers = new Headers(request.headers);
      if (UNSAFE_METHODS.has(request.method)) {
        const token = readCookie(cookies(), CSRF_COOKIE);
        if (token) headers.set('X-XSRF-TOKEN', token);
      }
      const tenant = expectedTenant();
      if (tenant) headers.set('X-Expected-Tenant', tenant);
      return new Request(request, { headers, credentials: 'include' });
    },
    async onResponse({ request, response }) {
      const expected = request.headers.get('X-Expected-Tenant');
      if (!expected) return undefined;
      const relayed = response.headers.get('X-Relayed-Tenant');
      const relayedElsewhere = relayed !== null && relayed.toLowerCase() !== expected.toLowerCase();
      if (relayedElsewhere || (response.status === 403 && (await errorFromResponse(response.clone())).code === 'TENANT_FORBIDDEN')) {
        throw new ApiError({ status: 409, code: 'TENANT_CHANGED', title: 'The selected tenant changed in another tab' });
      }
      return undefined;
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
