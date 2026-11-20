import { expect, test, type Page } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The built BackOffice against API answers stubbed in the browser (page.route): the shell's states, keyboard and axe
// on the real CSS. The real-stack flow through the BFF and Keycloak comes with issues 05 and 07 (ADR 0043).

const tenants = [
  {
    tenantId: 'aaaaaaaa-0000-4000-8000-000000000001',
    code: 'rest-a',
    legalName: 'Restaurante A, Lda',
    kind: 'ACCOUNT',
    status: 'ACTIVE',
  },
  {
    tenantId: 'bbbbbbbb-0000-4000-8000-000000000002',
    code: 'rest-b',
    legalName: 'Restaurante B, Lda',
    kind: 'ACCOUNT',
    status: 'ACTIVE',
  },
];
const stores = [
  { id: '11111111-0000-4000-8000-000000000001', code: 'BAIXA', name: 'Loja Baixa', active: true },
  { id: '22222222-0000-4000-8000-000000000002', code: 'SOMM', name: 'Loja Sommerschield', active: true },
];
const selectedStore = stores[1]!.id;
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  // One per sidebar area (apps/backoffice/src/shell/navigation.ts): axe checks the whole sidebar.
  permissions: [
    'PERM_TERMINAL_MANAGE',
    'PERM_STORE_MANAGE',
    'PERM_DINING_MANAGE',
    'PERM_CATALOG_MANAGE',
    'PERM_PRODUCTION_MANAGE',
    'PERM_INVENTORY_VIEW',
    'PERM_CUSTOMER_VIEW',
    'PERM_CASH_VIEW',
    'PERM_EMPLOYEE_MANAGE',
    'PERM_DELIVERY_ZONE_MANAGE',
    'PERM_ORDER_VIEW',
  ],
  tenantId: tenants[0]!.tenantId,
  tenantName: tenants[0]!.legalName,
};

async function signedIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) =>
    route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: stores.length } }),
  );
  // Unstubbed, the terminals page's list would reach the stack's BFF without a session and redirect to the login.
  await page.route('**/api/v1/backoffice/terminals?**', (route) =>
    route.fulfill({ json: { content: [], page: 0, size: 50, totalElements: 0 } }),
  );
}

test('a signed-out visitor is sent to the BFF login', async ({ page }) => {
  await page.route('**/api/v1/auth/**', (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: JSON.stringify({ status: 401, code: 'BFF_SESSION_EXPIRED', title: 'Unauthorized' }),
    }),
  );
  await page.route('**/oauth2/authorization/keycloak', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><html lang="pt"><title>Keycloak</title></html>' }),
  );

  await page.goto('/');

  await expect(page).toHaveURL(/\/oauth2\/authorization\/keycloak$/);
});

for (const viewport of [
  { width: 1280, height: 800 },
  { width: 1024, height: 768 },
]) {
  test(`the signed-in shell at ${viewport.width}×${viewport.height} works by keyboard and passes axe`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await signedIn(page);

    await page.goto(`/?storeId=${selectedStore}`);

    await expect(page.getByRole('heading', { level: 1, name: 'Visão geral' })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'pt');
    await expect(page.getByRole('combobox', { name: 'Loja' })).toHaveValue(selectedStore);
    await expectNoAxeViolations(page);

    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Saltar para o conteúdo' });
    await expect(skip).toBeFocused();
    await expect(skip).toBeInViewport();

    await page.keyboard.press('Tab');
    const overview = page.getByRole('navigation', { name: 'Áreas' }).getByRole('link', { name: 'Visão geral' });
    await expect(overview).toBeFocused();
    expect(await overview.evaluate((element) => getComputedStyle(element).boxShadow)).not.toBe('none');

    await page.keyboard.press('Tab');
    await expect(page.getByRole('navigation', { name: 'Áreas' }).getByRole('link', { name: 'Lojas' })).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1, name: 'Terminais' })).toBeVisible();
    await expect(page).toHaveURL(`/organization/terminals?storeId=${selectedStore}`);

    const switcher = page.getByRole('button', { name: 'Empresa: Restaurante A, Lda' });
    await switcher.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('menuitemradio', { name: 'Restaurante B, Lda' })).toBeVisible();
    await expectNoAxeViolations(page);
    await page.keyboard.press('Escape');
    await expect(switcher).toBeFocused();
  });
}

test('an area not built yet shows its page header, and passes axe', async ({ page }) => {
  await signedIn(page);

  await page.goto(`/catalog?storeId=${selectedStore}`);

  await expect(page.getByRole('heading', { level: 1, name: 'Catálogo' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Áreas' }).getByRole('link', { name: 'Catálogo' })).toHaveAttribute(
    'aria-current',
    'page',
  );
  await expect(page.getByText('Em preparação.')).toBeVisible();
  await expectNoAxeViolations(page);
});
