import { expect, test } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';
import { chmodSync, writeFileSync } from 'node:fs';
import { backOfficeCall, seeded, signInToBackOffice, stack } from '../support/stack';

// Demo seed for manual testing (opt-in: E2E_DEMO_SEED=1, else skipped): on top of the global setup's tenant, a fuller BackOffice (areas, tables,
// catalog with production destinations, waiters, terminals) and POS activity (open tables with sent rounds and a draft),
// all through the public API and the POS screens. Writes the logins to a 0600 file in the git-ignored .run/.

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
const list = (body: Json): Json[] => body.content ?? body;
const pin = () => String((randomBytes(4).readUInt32BE() % 9000) + 1000);

test('demo seed', async ({ browser }, testInfo) => {
  test.skip(!process.env.E2E_DEMO_SEED, 'opt-in: E2E_DEMO_SEED=1');
  test.setTimeout(600_000);
  const tenantId = seeded.tenantId();
  const storeId = seeded.storeId();
  const bo = await browser.newPage();
  await signInToBackOffice(bo, seeded.adminEmail(), seeded.adminPassword(), seeded.adminTotpSecret(), testInfo.retry);
  await backOfficeCall(bo, 'PUT', '/bff/tenant', { tenantId });
  const get = async (path: string) => (await backOfficeCall(bo, 'GET', path)).json();
  const post = async (path: string, data?: unknown, headers: Record<string, string> = {}) =>
    (await backOfficeCall(bo, 'POST', path, data, { 'X-Expected-Tenant': tenantId, ...headers })).json();

  // Terminals first: sensitive (D142), within the step-up age of this sign-in.
  const codes: Record<string, string> = {};
  for (const [code, name, kind] of [
    ['CX1', 'Caixa 1', 'POS'],
    ['BAR1', 'Bar', 'POS'],
    ['SEED', 'Seed (usado pelo script)', 'POS'],
    ['PB1', 'Impressoras', 'PRINT_BRIDGE'],
  ] as const) {
    const terminal = await post('/api/v1/backoffice/terminals', { code, name, storeId, kind }, { 'Idempotency-Key': randomUUID() });
    if (kind === 'POS') codes[name] = (await post(`/api/v1/backoffice/terminals/${terminal.id}/enrollment-code`)).code;
  }

  // Waiters.
  const roles = list(await get('/api/v1/backoffice/roles?size=100'));
  const waiterRole = roles.find((role) => role.name === 'Empregado de sala')!;
  const waiters: Record<string, string> = {};
  for (const [code, name] of [['JOAO', 'João Mabunda'], ['MARIA', 'Maria Cossa']] as const) {
    waiters[code] = pin();
    await post('/api/v1/backoffice/employees', { code, name, pin: waiters[code], storeId, roleId: waiterRole.id });
  }

  // Areas and tables: Sala already has M1 and M2.
  const sala = list(await get(`/api/v1/backoffice/areas?storeId=${storeId}`)).find((area) => area.code === 'SALA')!;
  for (let n = 3; n <= 10; n++) await post('/api/v1/backoffice/tables', { areaId: sala.id, code: `M${n}`, seats: n % 3 ? 4 : 6, storeId });
  const terrace = await post('/api/v1/backoffice/areas', { code: 'ESPLANADA', name: 'Esplanada', storeId });
  for (let n = 1; n <= 6; n++) await post('/api/v1/backoffice/tables', { areaId: terrace.id, code: `E${n}`, seats: 4, storeId });
  const bar = await post('/api/v1/backoffice/areas', { code: 'BAR', name: 'Bar', storeId });
  for (let n = 1; n <= 4; n++) await post('/api/v1/backoffice/tables', { areaId: bar.id, code: `B${n}`, seats: 2, storeId });

  // Catalog: new families and items with production destinations, priced on the store's price list, then published.
  const catalog = '/api/v1/backoffice/catalog';
  const families = Object.fromEntries(list(await get(`${catalog}/families?size=100`)).map((f) => [f.code, f.id]));
  for (const [code, name] of [['ENTRADAS', 'Entradas'], ['SOBREMESAS', 'Sobremesas'], ['CAFETARIA', 'Cafetaria']] as const) {
    families[code] = (await post(`${catalog}/families`, { code, name })).id;
  }
  const unit = list(await get(`${catalog}/units?size=100`)).find((u) => u.code === 'UN')!;
  const vat = list(await get(`${catalog}/tax-categories?size=100`)).find((t) => t.code === 'IVA16')!;
  const priceList = list(await get(`${catalog}/price-lists?storeId=${storeId}`))[0]!;
  const items: [string, string, string, string, number][] = [
    ['AMEIJOAS', 'Amêijoas à Bulhão Pato', 'ENTRADAS', 'KITCHEN', 420],
    ['RISSOIS', 'Rissóis de camarão', 'ENTRADAS', 'KITCHEN', 250],
    ['SOPA', 'Sopa do dia', 'ENTRADAS', 'KITCHEN', 150],
    ['FRANGO', 'Frango grelhado', 'PRATOS', 'KITCHEN', 400],
    ['MATAPA', 'Matapa com caranguejo', 'PRATOS', 'KITCHEN', 520],
    ['CAMARAO', 'Camarão grelhado', 'PRATOS', 'KITCHEN', 950],
    ['BACALHAU', 'Bacalhau à Brás', 'PRATOS', 'KITCHEN', 600],
    ['2M', 'Cerveja 2M', 'BEBIDAS', 'BAR', 90],
    ['LAURENTINA', 'Laurentina Preta', 'BEBIDAS', 'BAR', 100],
    ['VINHO', 'Vinho da casa (copo)', 'BEBIDAS', 'BAR', 150],
    ['REFRI', 'Refrigerante lata', 'BEBIDAS', 'BAR', 70],
    ['MOUSSE', 'Mousse de chocolate', 'SOBREMESAS', 'COPA', 180],
    ['PUDIM', 'Pudim', 'SOBREMESAS', 'COPA', 150],
    ['FRUTA', 'Salada de fruta', 'SOBREMESAS', 'COPA', 160],
    ['BICA', 'Bica', 'CAFETARIA', 'COPA', 50],
    ['GALAO', 'Galão', 'CAFETARIA', 'COPA', 80],
    ['CHA', 'Chá', 'CAFETARIA', 'COPA', 50],
  ];
  for (const [code, name, family, productionDestination, amount] of items) {
    const item = await post(`${catalog}/items`, { code, name, familyId: families[family], unitId: unit.id,
      taxCategoryId: vat.id, productionDestination, soldByWeight: false, kind: 'STANDARD' });
    await post(`${catalog}/price-lists/${priceList.id}/prices`, { itemId: item.id, amount });
  }
  const release = await post(`${catalog}/releases`, { storeId });
  await post(`${catalog}/releases/${release.id}/validate`);
  await post(`${catalog}/releases/${release.id}/publish`, { note: 'Demo seed' }, { 'Idempotency-Key': randomUUID() });
  await bo.close();

  // POS activity, through its screens on the "Seed" terminal.
  const pos = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  await pos.goto(`${stack.pos()}/enrol`);
  await pos.getByLabel('Código de registo').fill(codes['Seed (usado pelo script)']!);
  await pos.getByRole('button', { name: 'Registar terminal' }).click();
  await pos.getByLabel('Código de empregado').fill(seeded.waiterCode());
  await pos.getByRole('button', { name: 'Seguinte' }).click();
  await pos.getByLabel('PIN').fill(seeded.waiterPin());
  await pos.getByRole('button', { name: 'Entrar' }).click();
  await expect(pos.getByRole('heading', { name: 'Mesas' })).toBeVisible();

  const order = async (area: string, table: string, guests: number, rounds: string[][], draft: string[] = []) => {
    await pos.getByRole('tab', { name: area }).click();
    await pos.getByRole('button', { name: new RegExp(`^Mesa ${table} `) }).click();
    await pos.getByRole('complementary', { name: 'Detalhe da mesa' }).getByRole('button', { name: 'Abrir mesa' }).click();
    const dialog = pos.getByRole('dialog', { name: `Abrir mesa ${table}` });
    await dialog.getByLabel('Número de pessoas').fill(String(guests));
    await dialog.getByRole('button', { name: 'Abrir mesa' }).click();
    const panel = pos.getByRole('complementary', { name: 'Pedido' });
    await expect(panel.getByRole('heading', { name: `Mesa ${table}` })).toBeVisible();
    const drafts = panel.getByRole('list', { name: 'Por enviar' });
    let added = 0; // lines this script added since the last "Pedir": the list may lag, a count of its own does not
    const add = async (name: string) => {
      await pos.getByLabel('Pesquisar artigo ou código').fill(name);
      await pos.getByRole('list', { name: 'Artigos' }).getByRole('button', { name: new RegExp(`^${name}`) }).click();
      await expect(drafts.getByRole('listitem')).toHaveCount(++added);
    };
    for (const [index, round] of rounds.entries()) {
      for (const name of round) await add(name);
      await panel.getByRole('button', { name: 'Pedir' }).click();
      await expect(panel.getByRole('status', { name: 'Envio' })).toContainText(`Ronda ${index + 1} enviada`);
      await expect(panel.getByText('Sem linhas por enviar.')).toBeVisible();
      added = 0;
    }
    for (const name of draft) await add(name);
    await pos.getByRole('link', { name: 'Voltar às mesas' }).click();
    await expect(pos.getByRole('heading', { name: 'Mesas' })).toBeVisible();
  };
  await order('Sala', 'M3', 4, [['Amêijoas à Bulhão Pato', 'Camarão grelhado', 'Vinho da casa'], ['Mousse de chocolate']]);
  await order('Sala', 'M5', 2, [['Matapa com caranguejo', 'Cerveja 2M', 'Refrigerante lata']]);
  await order('Sala', 'M7', 6, [['Rissóis de camarão', 'Sopa do dia']], ['Bacalhau à Brás', 'Frango grelhado']);
  await order('Esplanada', 'E2', 2, [['Galão', 'Bica']]);
  await order('Bar', 'B1', 1, [['Laurentina Preta']]);

  const e = process.env;
  const file = `${e.E2E_RUN_DIR}/manual-credentials.txt`;
  writeFileSync(file, `restaurantPRO local stack: demo data (throwaway; gone after "pnpm e2e:stack:down")

BackOffice  ${stack.backOffice()}
  company code   ${e.E2E_TENANT_CODE}
  email          ${seeded.adminEmail()}
  password       ${seeded.adminPassword()}
  TOTP secret    ${seeded.adminTotpSecret()}

POS  ${stack.pos()}
  enrolment codes (one per terminal, single use; BackOffice > Terminais > Emitir código for a new one)
    Caixa 1        ${codes['Caixa 1']}
    Bar            ${codes.Bar}
    POS A          ${e.E2E_TERMINAL_A_CODE}
    POS B          ${e.E2E_TERMINAL_B_CODE}
  waiters (employee code / PIN)
    ${seeded.waiterCode()}      ${seeded.waiterPin()}
    JOAO           ${waiters.JOAO}
    MARIA          ${waiters.MARIA}

Seeded: Sala M1–M10, Esplanada E1–E6, Bar B1–B4; 17 more items over Entradas, Pratos, Bebidas, Sobremesas and Cafetaria
(Cozinha/Bar/Copa); open tables M3 (2 rounds), M5, M7 (1 round + 2 unsent lines), E2, B1.
`, { mode: 0o600 });
  chmodSync(file, 0o600);
  console.log(`written: ${file}`);
});
