import type { Page } from '@playwright/test';

// What the built POS needs before its first screen, stood in by page.route (the preview has no backend): Keycloak's
// token endpoint, the enrolment and the PIN login, then the steps a waiter takes to reach the table map.

export const STORE = '6f1c2a9e-0000-4000-8000-0000000000aa';
export const ENROLMENT = { id: '6f1c2a9e-0000-4000-8000-000000000001', storeId: STORE, clientId: 'pos-e2e' };
export const SESSION = {
  kind: 'EMPLOYEE',
  employeeId: '6f1c2a9e-0000-4000-8000-0000000000e1',
  storeId: STORE,
  name: 'Ana Silva',
  mustChangePin: false,
  permissions: [`PERM_DINING_VIEW@${STORE}`, `PERM_ORDER_EDIT@${STORE}`],
  posSession: 'pos-session-e2e',
};

export const id = (n: number) => `6f1c2a9e-0000-4000-8000-${String(n).padStart(12, '0')}`;

export async function standInDevice(page: Page) {
  await page.route('**/protocol/openid-connect/token', (route) =>
    route.fulfill({ json: { access_token: 'jwt-e2e', expires_in: 300, token_type: 'Bearer' } }),
  );
  await page.route('**/api/v1/pos/enrolment', (route) => route.fulfill({ json: ENROLMENT }));
  await page.route('**/api/v1/auth/pos-login', (route) => route.fulfill({ json: SESSION }));
}

export async function logIn(page: Page) {
  await page.goto('/enrol');
  await page.getByLabel('Código de registo').fill('K7M2P9QXABCDEFGH23456789');
  await page.getByRole('button', { name: 'Registar terminal' }).click();
  await page.getByLabel('Código de empregado').fill('1042');
  await page.getByRole('button', { name: 'Seguinte' }).click();
  await page.getByLabel('PIN').fill('739164');
  await page.getByRole('button', { name: 'Entrar' }).click();
}
