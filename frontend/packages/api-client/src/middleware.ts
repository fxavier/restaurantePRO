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

/** BackOffice (D184, FC §6): the BFF session cookie on every call, and the CSRF header on every unsafe one. */
export function backOfficeMiddleware(cookies: () => string = () => document.cookie): Middleware {
  return {
    onRequest({ request }) {
      const headers = new Headers(request.headers);
      if (UNSAFE_METHODS.has(request.method)) {
        const token = readCookie(cookies(), CSRF_COOKIE);
        if (token) headers.set('X-XSRF-TOKEN', token);
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
