import { expect, test } from '@playwright/test';
import { csrfToken, seeded, signInToBackOffice, stack } from '../support/stack';

// One sign-in only: Keycloak refuses a TOTP code twice, so every further sign-in of the run waits up to 30 s.
test('a seeded administrator signs in with a password and a TOTP code; the BFF session and CSRF hold through the origin', async ({
  page,
}, testInfo) => {
  await signInToBackOffice(page, seeded.adminEmail(), seeded.adminPassword(), seeded.adminTotpSecret(),
    testInfo.retry);

  await expect(page.getByRole('heading', { level: 1, name: 'Visão geral' })).toBeVisible();
  const cookies = await page.context().cookies(stack.backOffice());
  expect(cookies.find((c) => c.name === '__Host-bff-session')).toMatchObject({ httpOnly: true, secure: true });
  expect(cookies.find((c) => c.name === '__Host-XSRF-TOKEN')).toMatchObject({ httpOnly: false, secure: true });

  const tenant = { tenantId: seeded.tenantId() };
  const withoutToken = await page.request.put(`${stack.backOffice()}/bff/tenant`, { data: tenant });
  const withToken = await page.request.put(`${stack.backOffice()}/bff/tenant`, {
    data: tenant,
    headers: { 'X-XSRF-TOKEN': await csrfToken(page) },
  });
  const session = await page.request.get(`${stack.backOffice()}/api/v1/auth/session`);

  expect(withoutToken.status()).toBe(403);
  expect(withToken.status()).toBe(204);
  expect(session.status()).toBe(200);
  expect(await session.json()).toMatchObject({ kind: expect.any(String), permissions: expect.any(Array) });
});
