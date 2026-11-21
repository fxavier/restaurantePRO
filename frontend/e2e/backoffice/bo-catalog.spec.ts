import { expect, test, type Page } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The catalog screen of the built BackOffice, its API stood in by page.route (the preview has no BFF): the items list
// and the item form, then a store's releases and the publish dialog, with axe on each. With E2E_SCREENS set, the
// screens are also saved there at the DS BackOffice widths 1440 and 390, to compare with Stitch "Catálogo & Versões".

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_CATALOG_MANAGE', 'PERM_CATALOG_PUBLISH', 'PERM_STORE_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const store = { id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo' };
const families = [
  { id: 'f0000000-0000-4000-8000-000000000001', code: 'CARNES', name: 'Carnes', version: 0 },
  { id: 'f0000000-0000-4000-8000-000000000002', code: 'BEBIDAS', name: 'Bebidas', version: 0 },
];
const units = [{ id: 'a0000000-0000-4000-8000-000000000001', code: 'UN', name: 'Unidade', version: 0 }];
const taxCategories = [
  {
    id: 'c0000000-0000-4000-8000-000000000001',
    code: 'IVA16',
    name: 'IVA normal',
    rates: [{ id: 'c1000000-0000-4000-8000-000000000001', ratePercent: '16.00', effectiveFrom: '2026-01-01T00:00:00Z' }],
    version: 0,
  },
];
const item = (id: string, code: string, name: string, familyId: string, productionDestination: string) => ({
  id,
  code,
  name,
  familyId,
  unitId: units[0]!.id,
  taxCategoryId: taxCategories[0]!.id,
  productionDestination,
  kind: 'STANDARD',
  soldByWeight: false,
  availability: 'ACTIVE',
  version: 1,
});
const items = [
  item('e0000000-0000-4000-8000-000000000001', 'C09', 'Bife da Vazia', families[0]!.id, 'KITCHEN'),
  item('e0000000-0000-4000-8000-000000000002', 'B07', 'Sangria', families[1]!.id, 'BAR'),
];
const releases = [
  {
    id: 'aa000000-0000-4000-8000-000000000002',
    storeId: STORE,
    status: 'VALIDATED',
    createdAt: '2026-11-19T10:30:00Z',
    validatedAt: '2026-11-19T11:00:00Z',
    validationWarnings: [{ code: 'ITEM_WITHOUT_BARCODE', itemCode: 'C09', message: 'Bife da Vazia não tem código de barras' }],
    version: 1,
  },
  { id: 'aa000000-0000-4000-8000-000000000001', storeId: STORE, status: 'PUBLISHED', createdAt: '2026-11-01T08:00:00Z', effectiveAt: '2026-11-01T09:15:00Z', validationWarnings: [], version: 2 },
];
const pageOf = <T>(content: T[]) => ({ content, page: 0, size: 200, totalElements: content.length });

async function standIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) => route.fulfill({ json: pageOf([store]) }));
  await page.route(`**/api/v1/backoffice/stores/${STORE}`, (route) => route.fulfill({ json: store }));
  await page.route('**/api/v1/backoffice/catalog/items?**', (route) => route.fulfill({ json: pageOf(items) }));
  await page.route('**/api/v1/backoffice/catalog/families?**', (route) => route.fulfill({ json: pageOf(families) }));
  await page.route('**/api/v1/backoffice/catalog/units?**', (route) => route.fulfill({ json: pageOf(units) }));
  await page.route('**/api/v1/backoffice/catalog/tax-categories?**', (route) => route.fulfill({ json: pageOf(taxCategories) }));
  await page.route('**/api/v1/backoffice/catalog/releases?**', (route) => route.fulfill({ json: pageOf(releases) }));
}

async function screen(page: Page, name: string) {
  const dir = process.env.E2E_SCREENS;
  if (!dir) return;
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.screenshot({ path: `${dir}/${name}-${width}.png`, fullPage: true, animations: 'disabled' });
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

test('catalog: the items list and the item form, axe clean', async ({ page }) => {
  await standIn(page);

  await page.goto(`/catalog?storeId=${STORE}`);
  const table = page.getByRole('table', { name: 'Artigos do catálogo' });
  await expect(table.getByRole('row')).toHaveCount(items.length + 1);
  await expect(table.getByText('Bife da Vazia')).toBeVisible();
  await expectNoAxeViolations(page);
  await screen(page, 'catalog-items');

  await page.getByRole('button', { name: 'Novo artigo' }).click();
  await expect(page.getByRole('dialog', { name: 'Novo artigo' })).toBeVisible();
  await expectNoAxeViolations(page);
  await screen(page, 'catalog-item-form');
});

test("catalog: a store's releases and the publish dialog, axe clean", async ({ page }) => {
  await standIn(page);

  await page.goto(`/catalog?storeId=${STORE}&tab=versoes`);
  const table = page.getByRole('table', { name: 'Versões do catálogo da loja' });
  await expect(table.getByRole('row')).toHaveCount(releases.length + 1);
  await expect(table.getByRole('cell', { name: 'Validada' })).toBeVisible();
  await expectNoAxeViolations(page);
  await screen(page, 'catalog-releases');

  await table.getByRole('button', { name: 'Publicar' }).click();
  const dialog = page.getByRole('dialog', { name: 'Publicar versão' });
  await expect(dialog.getByLabel('Nota (opcional)')).toBeVisible();
  await expectNoAxeViolations(page);
  await screen(page, 'catalog-publish-dialog');
});
