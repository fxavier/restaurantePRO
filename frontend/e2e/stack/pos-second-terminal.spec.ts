import { expect, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { enrolAndLogIn, openTable, orderPanel, readAs, recordBatchBodies, replay, screens, tile } from '../support/pos';

// Flow (b) on the real stack (ticket 12, PRD 45-49, AC5-AC9, spec §14 #2, #6, #7): terminal A enrols with a code
// issued through the API, logs the waiter in with the PIN, opens M1 and sends a round of three lines (a variant, a
// menu's choices, a note); terminal B, a second terminal of the same store in its own browser context, sees M1
// occupied and then the round, live, without reloading. The batch request replayed with its Idempotency-Key makes no
// second round: checked through the API.
test.use({ viewport: { width: 1024, height: 768 } });

test('a round sent from one terminal shows on the second, live; a replayed "Pedir" makes no second round', async ({
  browser,
  request,
}) => {
  test.setTimeout(120_000); // two terminals: enrolment, PIN, live stream (opens at once since FE-F24) and a round
  const a = await (await browser.newContext({ viewport: { width: 1024, height: 768 } })).newPage();
  const b = await (await browser.newContext({ viewport: { width: 1024, height: 768 } })).newPage();
  await a.addInitScript(recordBatchBodies);
  await enrolAndLogIn(a, 'A', { check: true });
  await enrolAndLogIn(b, 'B');
  await expect(tile(b, 'M1')).toContainText('Livre');
  await expectNoAxeViolations(a);
  await screens(a, 'table-map');

  await openTable(a, 'M1', 2, { check: true });

  // B's map follows the change stream: no reload.
  await expect(tile(b, 'M1')).toContainText('Ocupada');
  await tile(b, 'M1').click();
  await expect(b.getByRole('heading', { name: 'Mesa M1' })).toBeVisible();
  await expect(orderPanel(b).getByText('Nada enviado ainda.')).toBeVisible();

  // A variant.
  await a.getByRole('button', { name: 'Bebidas' }).click();
  await a.getByRole('button', { name: /^Sumo natural/ }).click();
  const variant = a.getByRole('dialog', { name: 'Sumo natural' });
  await variant.getByRole('radio', { name: /^Grande/ }).check();
  await expectNoAxeViolations(a);
  await variant.getByRole('button', { name: 'Adicionar' }).click();
  const drafts = orderPanel(a).getByRole('list', { name: 'Por enviar' });
  await expect(drafts.getByRole('listitem')).toHaveCount(1);
  // A menu's choices.
  await a.getByRole('button', { name: 'Pratos' }).click();
  await a.getByRole('button', { name: /^Menu do dia/ }).click();
  const menu = a.getByRole('dialog', { name: 'Menu do dia' });
  await menu.getByRole('checkbox', { name: 'Bitoque' }).check();
  await menu.getByRole('checkbox', { name: 'Água 50cl' }).check();
  await menu.getByRole('button', { name: 'Adicionar' }).click();
  await expect(drafts.getByRole('listitem')).toHaveCount(2);
  // A note.
  await a.getByRole('button', { name: 'Bebidas' }).click();
  await a.getByRole('button', { name: /^Água 50cl/ }).click();
  await expect(drafts.getByRole('listitem')).toHaveCount(3);
  await drafts.getByRole('button', { name: 'Nota de Água 50cl' }).click();
  const note = a.getByRole('dialog', { name: 'Nota de Água 50cl' });
  await note.getByLabel('Nota para a cozinha (opcional)').fill('Sem gelo');
  await expectNoAxeViolations(a);
  await note.getByRole('button', { name: 'Guardar' }).click();
  await expect(drafts.getByRole('listitem').nth(2)).toContainText('Nota: Sem gelo');
  await expectNoAxeViolations(a);
  await screens(a, 'order-drafts');

  const [sent] = await Promise.all([
    a.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/batches')),
    orderPanel(a).getByRole('button', { name: 'Pedir' }).click(),
  ]);
  const answer = await (await sent.response())!.json();

  // A: the server's round, under "Enviado"; nothing left to send.
  const roundA = orderPanel(a).getByRole('list', { name: /^Ronda 1/ });
  await expect(roundA.getByRole('listitem')).toHaveCount(3);
  await expect(roundA).toContainText('Sumo natural Grande');
  await expect(roundA).toContainText('Menu do dia');
  await expect(roundA).toContainText('Nota: Sem gelo');
  await expect(orderPanel(a).getByText('Sem linhas por enviar.')).toBeVisible();
  await expect(orderPanel(a).getByRole('status', { name: 'Envio' })).toHaveText('Ronda 1 enviada.');
  await expectNoAxeViolations(a);
  await screens(a, 'order-sent');
  // B: the same round, live.
  await expect(orderPanel(b).getByRole('list', { name: /^Ronda 1/ }).getByRole('listitem')).toHaveCount(3);
  await expectNoAxeViolations(b);

  // The same intent again, with its key: the server answers the round it already made, and makes no other.
  const [body] = await a.evaluate(() => (window as unknown as { sentBatchBodies: string[] }).sentBatchBodies);
  const replayed = await replay(request, sent, body!);
  expect(replayed.ok(), `replay answered ${replayed.status()}: ${await replayed.text()}`).toBe(true);
  expect((await replayed.json()).id).toBe(answer.id);
  const order = await readAs(request, sent, `/api/v1/pos/orders/${answer.orderId}`);
  expect(order.batches).toHaveLength(1);
  expect(order.batches[0].id).toBe(answer.id);
  expect(order.lines.filter((line: { status: string }) => line.status === 'CONFIRMED')).toHaveLength(3);
});
