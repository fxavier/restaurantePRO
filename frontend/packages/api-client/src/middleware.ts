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
  /** The user (Keycloak subject, `X-Relayed-User`) this tab's page was loaded for; absent before the first session read. */
  expectedUser?: () => string | undefined;
  /** Told the user (`X-Relayed-User`) of every answer that names one, error answers included, so the tab can pin it. */
  onRelayedUser?: (subject: string) => void;
  /** The document's cookies; injectable for tests. */
  cookies?: () => string;
};

const tenantChanged = () =>
  new ApiError({ status: 409, code: 'TENANT_CHANGED', title: 'The session or tenant changed in another tab' });

/**
 * BackOffice (D184, FC §6): the BFF session cookie on every call and the CSRF header on every unsafe one. Once the tab
 * is pinned, every call names its tenant in `X-Expected-Tenant` (FE-F16) and its user in `X-Expected-User` (FE-F22).
 * The call throws `TENANT_CHANGED` instead of handing over an answer when that tenant or user no longer holds: the
 * answer was relayed in another tenant (`X-Relayed-Tenant`) or for another user (`X-Relayed-User`), or the backend
 * refused the expected tenant to the session's user (403 `TENANT_FORBIDDEN`: another user logged in from another
 * tab). A write the BFF refused (409 `TENANT_CHANGED` or `SESSION_CHANGED`) comes back as the BFF's error.
 */
export function backOfficeMiddleware({
  expectedTenant,
  expectedUser = () => undefined,
  onRelayedUser = () => undefined,
  cookies = () => document.cookie,
}: BackOfficeOptions): Middleware {
  return {
    onRequest({ request }) {
      const headers = new Headers(request.headers);
      if (UNSAFE_METHODS.has(request.method)) {
        const token = readCookie(cookies(), CSRF_COOKIE);
        if (token) headers.set('X-XSRF-TOKEN', token);
      }
      const tenant = expectedTenant();
      if (tenant) headers.set('X-Expected-Tenant', tenant);
      const user = expectedUser();
      if (user) headers.set('X-Expected-User', user);
      return new Request(request, { headers, credentials: 'include' });
    },
    async onResponse({ request, response }) {
      const relayedUser = response.headers.get('X-Relayed-User');
      if (relayedUser !== null) {
        onRelayedUser(relayedUser);
        // The pin now, not only the one the call was sent with: the first answers of a page load race, and another
        // login between them must not leave a tab showing one user's data while its writes name another.
        const pinnedUser = expectedUser();
        if (pinnedUser && relayedUser !== pinnedUser) throw tenantChanged();
      }
      const expected = request.headers.get('X-Expected-Tenant');
      if (!expected) return undefined;
      const relayed = response.headers.get('X-Relayed-Tenant');
      const relayedElsewhere = relayed !== null && relayed.toLowerCase() !== expected.toLowerCase();
      if (relayedElsewhere || (response.status === 403 && (await errorFromResponse(response.clone())).code === 'TENANT_FORBIDDEN')) {
        throw tenantChanged();
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
