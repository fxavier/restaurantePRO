import { expect, type APIRequestContext, type Page, type Request } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { openFromMap, tile } from '../pos/stand-in';
import { seeded, stack } from './stack';

export { openFromMap, tile };

/** DS: the POS viewports; screens are saved at each when E2E_SCREENS names a directory (for the PR). */
export const POS_VIEWPORTS = [
  { width: 1024, height: 768 },
  { width: 1366, height: 768 },
] as const;

export async function screens(page: Page, name: string) {
  const dir = process.env.E2E_SCREENS;
  if (!dir) return;
  const viewport = page.viewportSize();
  for (const { width, height } of POS_VIEWPORTS) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${dir}/stack-${name}-${width}x${height}.png`, animations: 'disabled' });
  }
  if (viewport) await page.setViewportSize(viewport);
}

/** Enrols this browser as terminal `name` with its seeded code, then logs the waiter in with the PIN pad. */
export async function enrolAndLogIn(page: Page, name: 'A' | 'B' | 'C', { check = false } = {}) {
  await page.goto(`${stack.pos()}/enrol`);
  await page.getByLabel('Código de registo').fill(seeded.terminal(name).code);
  if (check) await expectNoAxeViolations(page);
  await page.getByRole('button', { name: 'Registar terminal' }).click();
  await page.getByLabel('Código de empregado').fill(seeded.waiterCode());
  if (check) await expectNoAxeViolations(page);
  await page.getByRole('button', { name: 'Seguinte' }).click();
  await page.getByLabel('PIN').fill(seeded.waiterPin());
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('heading', { name: 'Mesas' })).toBeVisible();
  await expectLive(page);
}

/** The change stream open: the backend answers it at once with `:ok`; the client gives a start up after 15 s. */
export async function expectLive(page: Page, timeout = 15_000) {
  await expect(page.getByRole('status', { name: 'Ligação' })).toHaveText(/Ligado/, { timeout });
}

export const orderPanel = (page: Page) => page.getByRole('complementary', { name: 'Pedido' });

/** Opens a free table for `guests` from the map and waits for its order screen. */
export async function openTable(page: Page, code: string, guests: number, { check = false } = {}) {
  await openFromMap(page, code);
  const dialog = page.getByRole('dialog', { name: `Abrir mesa ${code}` });
  await dialog.getByRole('button', { name: String(guests), exact: true }).click();
  if (check) await expectNoAxeViolations(page);
  await dialog.getByRole('button', { name: 'Abrir mesa' }).click();
  await expect(orderPanel(page).getByRole('heading', { name: `Mesa ${code}` })).toBeVisible();
}

/**
 * An init script: keeps the body of every "Pedir" the page sends in `window.sentBatchBodies`. Chromium does not report
 * it (the app's client re-wraps each Request with its timeout signal, which makes the body a stream).
 */
export function recordBatchBodies() {
  const recorded: string[] = [];
  (window as unknown as { sentBatchBodies: string[] }).sentBatchBodies = recorded;
  const original = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    if (input instanceof Request && input.method === 'POST' && input.url.endsWith('/batches')) {
      recorded.push(await input.clone().text());
    }
    return original(input, init);
  };
}

/**
 * The same call again: the body the app sent, with the terminal's bearer, its POS session and the Idempotency-Key it
 * sent, from the POS origin. The headers are never printed.
 */
export async function replay(request: APIRequestContext, sent: Request, body: string) {
  const headers = await sent.allHeaders();
  return request.fetch(sent.url(), {
    method: sent.method(),
    data: body,
    headers: {
      authorization: headers.authorization!,
      'x-pos-session': headers['x-pos-session']!,
      'idempotency-key': headers['idempotency-key']!,
      'content-type': 'application/json',
    },
  });
}

/** A GET with the credentials of a request the app sent: what the server holds, read through the API. */
export async function readAs(request: APIRequestContext, sent: Request, path: string) {
  const headers = await sent.allHeaders();
  const response = await request.get(`${stack.pos()}${path}`, {
    headers: { authorization: headers.authorization!, 'x-pos-session': headers['x-pos-session']! },
  });
  expect(response.status(), `GET ${path}`).toBe(200);
  return response.json();
}
