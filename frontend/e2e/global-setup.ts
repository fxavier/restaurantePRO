import { chromium, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { randomBase32 } from './support/totp';
import { backOfficeCall, required, signInToBackOffice, stack } from './support/stack';

const SEED_SCRIPT = fileURLToPath(new URL('../../deploy/keycloak-e2e-seed.sh', import.meta.url));
const WAITER_ROLE = 'Empregado de sala';

/**
 * Seeds one run's data on the compose stack, through the public API and the provisioners only (ADR 0043, never SQL):
 *   1. deploy/keycloak-e2e-seed.sh: a new tenant (platform API) with its first store, and its administrator in
 *      Keycloak with a password and a TOTP secret known to the tests;
 *   2. as that administrator, through the BFF (password + TOTP, the BackOffice's own path): a waiter with a PIN in the
 *      store, and a catalog of one priced item, validated and published for the store.
 * Everything is unique per run: a new tenant scopes every code. The values reach the tests through process.env.
 */
export default async function globalSetup() {
  const run = `${Date.now().toString(36)}${randomBytes(3).toString('hex')}`;
  const admin = {
    E2E_TENANT_CODE: `e2e-${run}`,
    E2E_ADMIN_EMAIL: `e2e-admin-${run}@restaurantpro.test`,
    E2E_ADMIN_PASSWORD: `Pw-${randomBase32(24, randomBytes)}`,
    E2E_ADMIN_TOTP_SECRET: process.env.E2E_ADMIN_TOTP_SECRET || randomBase32(32, randomBytes),
  };
  // The secrets go to the script through its environment, never its arguments (they are never printed).
  const output = execFileSync('bash', [SEED_SCRIPT, '--url', stack.keycloak(), '--backend-url', stack.backend(),
    '--admin-password-file', required('E2E_KEYCLOAK_ADMIN_PASSWORD_FILE')], {
    env: { ...process.env, ...admin },
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  const { tenantId } = JSON.parse(output.toString()) as { tenantId: string };
  Object.assign(process.env, admin, { E2E_RUN_ID: run, E2E_TENANT_ID: tenantId });

  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await signInToBackOffice(page, admin.E2E_ADMIN_EMAIL, admin.E2E_ADMIN_PASSWORD, admin.E2E_ADMIN_TOTP_SECRET);
    await backOfficeCall(page, 'PUT', '/bff/tenant', { tenantId });
    Object.assign(process.env, await seedStoreData(page, tenantId));
  } finally {
    await browser.close();
  }
}

/** Seeds through the BFF as the SPA writes: every write names the selected company (X-Expected-Tenant, FE-F16). */
async function seedStoreData(page: Page, tenantId: string) {
  const get = async (path: string) => (await backOfficeCall(page, 'GET', path)).json();
  const post = async (path: string, data?: unknown, headers?: Record<string, string>) =>
    (await backOfficeCall(page, 'POST', path, data, { 'X-Expected-Tenant': tenantId, ...headers })).json();
  const catalog = '/api/v1/backoffice/catalog';

  const stores = await get('/api/v1/backoffice/stores');
  const storeId: string = stores.content[0].id;
  const roles = await get('/api/v1/backoffice/roles?size=100');
  const waiterRole = roles.content.find((role: { name: string }) => role.name === WAITER_ROLE);
  if (!waiterRole) throw new Error(`no role "${WAITER_ROLE}" in the new tenant`);

  const waiter = { code: 'EMPREGADO', pin: String(randomBytes(4).readUInt32BE() % 9000 + 1000) };
  await post('/api/v1/backoffice/employees', { code: waiter.code, name: 'Empregado E2E', pin: waiter.pin, storeId,
    roleId: waiterRole.id });

  const family = await post(`${catalog}/families`, { code: 'BEBIDAS', name: 'Bebidas' });
  const unit = await post(`${catalog}/units`, { code: 'UN', name: 'Unidade' });
  const vat = await post(`${catalog}/tax-categories`, { code: 'IVA16', name: 'IVA 16 %' });
  await post(`${catalog}/tax-categories/${vat.id}/rates`, { ratePercent: 16, effectiveFrom: '2000-01-01T00:00:00Z' });
  const item = await post(`${catalog}/items`, { code: 'AGUA', name: 'Água 50cl', familyId: family.id, unitId: unit.id,
    taxCategoryId: vat.id, productionDestination: 'BAR', soldByWeight: false, kind: 'STANDARD' });
  const priceList = await post(`${catalog}/price-lists`, { storeId, name: 'Sala' });
  await post(`${catalog}/price-lists/${priceList.id}/prices`, { itemId: item.id, amount: 60 });
  const release = await post(`${catalog}/releases`, { storeId });
  await post(`${catalog}/releases/${release.id}/validate`);
  await post(`${catalog}/releases/${release.id}/publish`, { note: 'E2E seed' }, { 'Idempotency-Key': randomUUID() });

  return {
    E2E_STORE_ID: storeId,
    E2E_WAITER_CODE: waiter.code,
    E2E_WAITER_PIN: waiter.pin,
    E2E_CATALOG_ITEM_ID: item.id as string,
    E2E_CATALOG_RELEASE_ID: release.id as string,
  };
}
