import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The production area of the built BackOffice ("Produção, Encaminhamento & Impressoras"), its API stood in by
// page.route (the preview has no BFF): the summary, the printer cards, the print queue, the tickets with no route and
// the routing matrix, then the "Adicionar impressora" and "Modo" dialogs, with axe on each. Screenshots at 1024 × 768
// and 1440 × 900 only when E2E_SCREENS names a directory.

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_PRODUCTION_MANAGE', 'PERM_PRODUCTION_OPERATE', 'PERM_PRODUCTION_VIEW', 'PERM_TERMINAL_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const profile = { paperWidthMm: 80, columns: 48, cutMode: 'PARTIAL', feedLines: 4, statusProbe: true, drawerPulseMs: 100 };
const printer = (id: string, code: string, name: string, host: string, extra: Record<string, unknown> = {}) => ({
  id: `cccc0000-0000-4000-8000-00000000000${id}`,
  storeId: STORE,
  code,
  name,
  host,
  port: 9100,
  codePage: 'CP860',
  active: true,
  mode: 'NORMAL',
  version: 1,
  profile,
  ...extra,
});
const kitchen = printer('1', 'COZ', 'Cozinha Quentes', '192.168.1.181');
const copa = printer('2', 'COPA', 'Copa & Linha Fria', '192.168.1.182', { mode: 'HOLD' });
const bar = printer('3', 'BAR', 'Bar Central', '192.168.1.183', { mode: 'REDIRECT', redirectPrinterId: kitchen.id });
// The fourth is ignored and inactive, so each mode's pill (and "Inativa") is on screen.
const office = printer('4', 'ESC', 'Escritório', '192.168.1.184', { mode: 'IGNORE', active: false });
const printers = [kitchen, copa, bar, office];
const terminals = [
  { id: 'aaaa0000-0000-4000-8000-000000000001', storeId: STORE, code: 'CX1', name: 'Caixa 1', status: 'ACTIVE', kind: 'POS' },
  { id: 'aaaa0000-0000-4000-8000-000000000002', storeId: STORE, code: 'SALA', name: 'Sala', status: 'ACTIVE', kind: 'POS' },
];
const routes = [
  { destinationCode: 'COZINHA', printerId: kitchen.id },
  { destinationCode: 'COPA', printerId: copa.id },
  { destinationCode: 'BAR', printerId: bar.id },
];
const job = (n: string, extra: Record<string, unknown>) => ({
  id: `dddd0000-0000-4000-8000-00000000000${n}`,
  storeId: STORE,
  printerId: kitchen.id,
  destination: 'COZINHA',
  sourceType: 'BATCH',
  kind: 'ORIGINAL',
  status: 'SENT',
  attempts: 1,
  createdAt: '2026-11-20T12:15:24Z',
  ...extra,
});
const jobs = [
  job('1', {}),
  job('2', { destination: 'BAR', printerId: bar.id, createdAt: '2026-11-20T12:11:02Z' }),
  job('3', { sourceType: 'SUBTOTAL', destination: null, status: 'FAILED', attempts: 5, lastError: 'Connection refused' }),
  job('4', { kind: 'COPY', status: 'UNKNOWN', createdAt: '2026-11-20T12:02:18Z' }),
  // The other states and both resolutions, so every pill of the queue is on screen.
  job('5', { status: 'QUEUED', attempts: 0, createdAt: '2026-11-20T12:16:00Z' }),
  job('6', { status: 'PROCESSING', createdAt: '2026-11-20T12:16:10Z' }),
  job('7', { status: 'RETRY_WAIT', attempts: 2, createdAt: '2026-11-20T12:16:20Z' }),
  job('8', { status: 'FAILED', attempts: 5, resolution: 'RETRIED', createdAt: '2026-11-20T11:50:00Z' }),
  job('9', { status: 'UNKNOWN', resolution: 'CONFIRMED_PRINTED', createdAt: '2026-11-20T11:45:00Z' }),
];
const tickets = [
  {
    id: 'eeee0000-0000-4000-8000-000000000001',
    storeId: STORE,
    destination: 'SOBREMESAS',
    sourceType: 'BATCH',
    kind: 'ORIGINAL',
    routing: 'UNROUTED',
    lines: ['1x Pudim', '2x Mousse de chocolate'],
    createdAt: '2026-11-20T12:10:00Z',
  },
];
const PRODUCTION = `/production?storeId=${STORE}`;

const pageOf = (content: unknown[], size: number) => ({ content, page: 0, size, totalElements: content.length });

/** The list answers, filtered the way the server filters them (`status`, `routing`). */
function filtered(route: Route, all: Record<string, unknown>[], param: 'status' | 'routing') {
  const url = new URL(route.request().url());
  const value = url.searchParams.get(param);
  const size = Number(url.searchParams.get('size') ?? 20);
  const matching = all.filter((one) => !value || one[param] === value);
  return route.fulfill({ json: { ...pageOf(matching.slice(0, size), size), totalElements: matching.length } });
}

async function standIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) => route.fulfill({ json: pageOf(stores, 100) }));
  await page.route('**/api/v1/backoffice/terminals?**', (route) => route.fulfill({ json: pageOf(terminals, 100) }));
  await page.route('**/api/v1/backoffice/printers?**', (route) => route.fulfill({ json: pageOf(printers, 100) }));
  await page.route('**/api/v1/backoffice/printer-profiles', (route) =>
    route.fulfill({ json: [{ name: 'EPSON_TM_80', codePage: 'CP860', profile }] }),
  );
  await page.route('**/api/v1/backoffice/production-routes?**', (route) => route.fulfill({ json: { storeId: STORE, routes } }));
  await page.route('**/api/v1/backoffice/terminal-printers?**', (route) =>
    route.fulfill({ json: { storeId: STORE, assignments: [{ terminalId: terminals[0]!.id, printerId: bar.id }] } }),
  );
  await page.route('**/api/v1/backoffice/print-jobs?**', (route) => filtered(route, jobs, 'status'));
  await page.route('**/api/v1/backoffice/production-tickets?**', (route) => filtered(route, tickets, 'routing'));
}

/** Only with E2E_SCREENS, and once (from the desktop run): the page at 1024 × 768 and 1440 × 900, then back. */
async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  const dir = process.env.E2E_SCREENS;
  const viewport = page.viewportSize()!;
  if (!dir || viewport.width < 1024) return;
  for (const { width, height } of [
    { width: 1024, height: 768 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize({ width, height });
    const path = `${dir}/${name}-${width}x${height}.png`;
    await page.screenshot({ path, fullPage: true, animations: 'disabled' });
    testInfo.annotations.push({ type: 'screenshot', description: path });
  }
  await page.setViewportSize(viewport);
}

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
]) {
  test(`production at ${viewport.width}: printers, queue, routing and the dialogs, axe clean`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await standIn(page);

    await page.goto(PRODUCTION);
    await expect(page.getByRole('heading', { name: 'Produção, Encaminhamento & Impressoras' })).toBeVisible();
    const cards = page.getByRole('list', { name: 'Impressoras da loja' });
    await expect(cards.getByRole('listitem', { name: 'Bar Central' }).getByText('Envia para Cozinha Quentes')).toBeVisible();
    await expect(page.getByRole('list', { name: 'Trabalhos de impressão' }).getByRole('listitem')).toHaveCount(jobs.length);
    await expect(page.getByRole('list', { name: 'Talões por encaminhar' }).getByRole('listitem')).toHaveCount(1);
    await expect(page.getByRole('form', { name: 'Rotas por destino' }).getByRole('textbox', { name: 'Destino 3' })).toHaveValue('BAR');
    await expect(page.getByRole('form', { name: 'Impressora por terminal' }).getByRole('combobox', { name: 'Caixa 1' })).toHaveValue(bar.id);
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'bo-production');

    await page.getByRole('button', { name: 'Adicionar impressora' }).click();
    const add = page.getByRole('dialog', { name: 'Adicionar impressora' });
    await expect(add.getByLabel('Porta')).toHaveValue('9100');
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'bo-production-add-printer');
    await add.getByRole('button', { name: 'Cancelar' }).click();
    await expect(add).toBeHidden();

    await page.getByRole('button', { name: 'Modo de Copa & Linha Fria' }).click();
    const mode = page.getByRole('dialog', { name: 'Modo de Copa & Linha Fria' });
    await expect(mode.getByRole('radio', { name: 'Em espera' })).toBeChecked();
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'bo-production-mode');
  });
}
