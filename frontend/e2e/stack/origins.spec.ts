import { expect, test } from '@playwright/test';
import { request as httpRequest } from 'node:http';
import { stack } from '../support/stack';

// ADR 0041: the POS origin reaches the backend for /api/v1/pos/** and /api/v1/auth/** only. An anonymous call to those
// is the backend's 401; any other API path is the proxy's 404 and never reaches the backend.
const forwarded = ['/api/v1/pos/catalog', '/api/v1/auth/session'];
const notForwarded = [
  '/api/v1/backoffice/stores',
  '/api/v1/platform/tenants',
  '/api/v1/posx',
  '/api/v2/pos/catalog',
];
// Sent byte for byte (a URL parser would resolve the dot segments before the request leaves).
const traversals = ['/api/v1/pos/../backoffice/stores', '/api/v1/pos/%2e%2e/backoffice/stores',
  '/api/v1/auth/..%2fbackoffice/stores', '/api/v1/pos/..;/backoffice/stores'];

function rawGetStatus(origin: string, path: string): Promise<number> {
  const { hostname, port } = new URL(origin);
  return new Promise((resolve, reject) => {
    httpRequest({ hostname, port, path, method: 'GET' }, (response) => {
      response.resume();
      resolve(response.statusCode ?? 0);
    }).on('error', reject).end();
  });
}

test.describe('the POS origin', () => {
  for (const path of forwarded) {
    test(`forwards ${path} to the backend`, async ({ request }) => {
      const response = await request.get(`${stack.pos()}${path}`);

      expect(response.status()).toBe(401);
    });
  }

  for (const path of notForwarded) {
    test(`answers 404 to ${path}`, async ({ request }) => {
      const response = await request.get(`${stack.pos()}${path}`);

      expect(response.status()).toBe(404);
    });
  }

  for (const path of traversals) {
    test(`never lets ${path} reach a backend route outside the two prefixes`, async () => {
      const status = await rawGetStatus(stack.pos(), path);

      expect([400, 404]).toContain(status);
    });
  }

  test('serves the SPA on any other path (client-side routes)', async ({ request }) => {
    const response = await request.get(`${stack.pos()}/tables`);

    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain('text/html');
  });
});

test.describe('the BackOffice origin', () => {
  test('answers an anonymous API call with the BFF, never the backend', async ({ request }) => {
    const response = await request.get(`${stack.backOffice()}/api/v1/auth/session`);

    expect(response.status()).toBe(401);
    expect(await response.json()).toMatchObject({ code: 'BFF_SESSION_EXPIRED' });
  });

  test('does not forward the POS API', async ({ request }) => {
    const response = await request.get(`${stack.backOffice()}/api/v1/pos/catalog`);

    expect(response.status()).toBe(404);
  });
});
