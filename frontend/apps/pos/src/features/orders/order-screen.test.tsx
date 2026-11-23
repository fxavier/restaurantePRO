import { act, screen, waitFor, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  addLineEndpoint,
  catalogEndpoint,
  created,
  deleteLineEndpoint,
  draft,
  ITEM,
  json,
  LINE,
  type LineView,
  noContent,
  ORDER,
  ORDER_ID,
  updateLineEndpoint,
  type UpdateLineRequest,
  VARIANT,
  withLines,
  orderEndpoint,
} from '../../test/order';
import { atOrder, drafts, panel, ready } from '../../test/order-screen';
import { renderPos } from '../../test/render-pos';
import { notificationsEndpoint, problem, tableMap, tablesEndpoint, token, tokenEndpoint } from '../../test/server';
import { MAP } from '../../test/table-map';

const added: LineView = draft(899, { itemId: ITEM.steak.id, gross: { amount: '450.00', currency: 'MZN' } });

const products = () => screen.getByRole('list', { name: 'Artigos' });
const tile = (name: RegExp) => within(products()).getByRole('button', { name });
const familyRail = () => screen.getByRole('navigation', { name: 'Famílias' });

describe('order screen', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a labelled loading state while the order is read', async () => {
    await atOrder([
      async () => {
        await delay('infinite');
        return HttpResponse.json(ORDER);
      },
    ]);

    expect(await screen.findByRole('status', { name: 'A carregar o pedido…' })).toBeInTheDocument();
  });

  it('an order that does not exist says so, with the way back', async () => {
    await atOrder([() => problem(404, 'HTTP_404')]);

    expect(await screen.findByRole('alert')).toHaveTextContent('Pedido não encontrado.');
    expect(screen.getByRole('link', { name: 'Voltar às mesas' })).toBeInTheDocument();
  });

  it('an order that cannot be read offers "Tentar novamente"', async () => {
    // Broken until "Tentar novamente": any read before it (the stream's opening reads every query again) fails too,
    // so no read order can make the alert go before the test sees it.
    let broken = true;
    const { user, order } = await atOrder([() => (broken ? problem(500, 'INTERNAL') : HttpResponse.json(ORDER))]);

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o pedido. Erro inesperado (INTERNAL).');
    const failedReads = order.length;
    broken = false;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('heading', { name: 'Mesa M2' })).toBeInTheDocument();
    expect(order.length).toBeGreaterThan(failedReads);
  });

  it('a catalog that cannot be read says so in the product area, and the order still shows', async () => {
    tokenEndpoint(token('jwt-1'));
    tablesEndpoint(tableMap(MAP));
    catalogEndpoint(() => problem(404, 'HTTP_404'));
    orderEndpoint(ORDER_ID, json(ORDER));
    const { router } = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
    await screen.findByRole('heading', { name: 'Mesas' });
    await act(() => router.navigate(`/orders/${ORDER_ID}`));

    expect(await screen.findByRole('heading', { name: 'Mesa M2' })).toBeInTheDocument();
    expect(await screen.findByText('Esta loja não tem um catálogo publicado.')).toBeInTheDocument();
  });

  it('shows the drafts under "Por enviar" and the sent lines per round under "Enviado", with the server prices', async () => {
    await atOrder();
    await ready();

    expect(within(panel()).getByText('3 pessoas · Pedido n.º 12')).toBeInTheDocument();
    const [water, coffee] = within(drafts()).getAllByRole('listitem');
    expect(water).toHaveTextContent('2 × Água Pequena');
    expect(water).toHaveTextContent('Nota: Sem gelo');
    expect(water).toHaveTextContent('120,00 MTn');
    expect(water).toHaveTextContent('Preço provisório');
    expect(coffee).toHaveTextContent('1 × Bica');
    const sent = within(panel()).getByRole('list', { name: 'Ronda 1, 12:10' });
    const [steak] = within(sent).getAllByRole('listitem');
    expect(steak).toHaveTextContent('3 × Bitoque');
    expect(steak).toHaveTextContent('1350,00 MTn');
    expect(steak).not.toHaveTextContent('Preço provisório');
  });

  it('marks where each item is prepared, on its product tile and on its lines (from the catalog)', async () => {
    await atOrder();
    await ready();

    expect(tile(/^Bica/)).toHaveTextContent('Bar');
    expect(tile(/^Bica/)).toHaveTextContent('#BICA');
    const [, coffee] = within(drafts()).getAllByRole('listitem');
    expect(within(coffee!).getByText('Bar')).toHaveClass('border-route-bar');
    const [steak] = within(within(panel()).getByRole('list', { name: 'Ronda 1, 12:10' })).getAllByRole('listitem');
    expect(within(steak!).getByText('Cozinha')).toHaveClass('border-route-kitchen');
    expect(within(panel()).getByText('1 ronda')).toBeInTheDocument();
  });

  it('"Por enviar" ends with the server\'s draft total, marked provisional and never a sum made here', async () => {
    await atOrder([json({ ...ORDER, draftGross: { amount: 777.77, currency: 'MZN' } })]);
    await ready();

    const subtotal = within(panel()).getByRole('group', { name: 'Subtotal por enviar' });
    expect(subtotal).toHaveTextContent('777,77 MTn');
    expect(subtotal).toHaveTextContent('Provisório');
  });

  it('shows no draft total without drafts', async () => {
    await atOrder([json(withLines(ORDER, [LINE.steak], { catalogVersion: undefined, draftGross: undefined }))]);
    await ready();

    expect(within(panel()).queryByRole('group', { name: 'Subtotal por enviar' })).not.toBeInTheDocument();
  });

  it('the total is the server\'s due, never a sum made here', async () => {
    // A due no sum of the lines gives: the screen shows it as it came.
    await atOrder([json({ ...ORDER, due: { amount: '999.99', currency: 'MZN' } })]);
    await ready();

    const total = within(panel()).getByRole('group', { name: 'Total' });
    expect(total).toHaveTextContent('999,99 MTn');
    expect(total).toHaveTextContent('Não inclui as linhas por enviar');
  });

  it('an empty order says so', async () => {
    await atOrder([json(withLines(ORDER, [], { batches: [], due: { amount: '0.00', currency: 'MZN' } }))]);
    await ready();

    expect(within(panel()).getByText('Sem linhas por enviar.')).toBeInTheDocument();
    expect(within(panel()).getByText('Nada enviado ainda.')).toBeInTheDocument();
  });

  it('browses the products by family; a subfamily\'s items show under its family; fees never show', async () => {
    const { user } = await atOrder();
    await ready();

    const rail = familyRail();
    expect(within(rail).getAllByRole('button').map((b) => b.textContent)).toEqual(['Bebidas', 'Pratos', 'Menus']);
    expect(within(rail).getByRole('button', { name: 'Bebidas' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(products()).getAllByRole('button').map((b) => b.textContent)).toEqual([
      expect.stringContaining('Bica'),
      expect.stringContaining('Água'),
      expect.stringContaining('Sumo de laranja'),
    ]);

    await user.click(within(rail).getByRole('button', { name: 'Pratos' }));

    expect(within(products()).getAllByRole('button').map((b) => b.textContent)).toEqual([
      expect.stringContaining('Bitoque'),
      expect.stringContaining('Frango grelhado'),
    ]);
    expect(screen.queryByText('Taxa de entrega')).not.toBeInTheDocument();
  });

  it('search finds by name, ignoring accents and case, across families', async () => {
    const { user } = await atOrder();
    await ready();

    await user.type(screen.getByRole('searchbox', { name: 'Pesquisar artigo ou código' }), 'agua');

    expect(within(products()).getAllByRole('button').map((b) => b.textContent)).toEqual([expect.stringContaining('Água')]);
    expect(within(familyRail()).getByRole('button', { name: 'Bebidas' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('search with no match says so', async () => {
    const { user } = await atOrder();
    await ready();

    await user.type(screen.getByRole('searchbox', { name: 'Pesquisar artigo ou código' }), 'lagosta');

    expect(screen.getByText('Nenhum artigo encontrado.')).toBeInTheDocument();
  });

  it('a tap adds one line: one request with its own Idempotency-Key, then the order is read again', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user, order } = await atOrder([json(ORDER), json(withLines(ORDER, [...ORDER.lines!, added]))]);
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));

    await user.click(tile(/^Bitoque/));

    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')).toHaveLength(3));
    expect(lines).toHaveLength(1);
    expect(lines[0]!.body).toEqual({ itemId: ITEM.steak.id, quantity: '1', components: [] });
    expect(lines[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(order.length).toBeGreaterThanOrEqual(2);
  });

  it('each tap is its own intent with its own key; a double tap while in flight sends one request', async () => {
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    const lines = addLineEndpoint(
      ORDER_ID,
      async () => {
        await released;
        return HttpResponse.json(added, { status: 201 });
      },
      created(added),
    );
    const { user } = await atOrder();
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));

    await user.dblClick(tile(/^Bitoque/));
    expect(await screen.findByRole('status', { name: 'A adicionar' })).toHaveTextContent('A adicionar Bitoque…');
    expect(tile(/^Bitoque/)).toBeDisabled();
    release();
    await waitFor(() => expect(tile(/^Bitoque/)).toBeEnabled());
    expect(lines).toHaveLength(1);

    await user.click(tile(/^Bitoque/));

    await waitFor(() => expect(lines).toHaveLength(2));
    expect(lines[1]!.idempotencyKey).not.toBe(lines[0]!.idempotencyKey);
  });

  it('a network failure offers "Tentar novamente", which resends with the same Idempotency-Key', async () => {
    const lines = addLineEndpoint(ORDER_ID, () => HttpResponse.error(), created(added));
    const { user } = await atOrder();
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));

    await user.click(tile(/^Bitoque/));

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — Bitoque não foi confirmado.');
    expect(tile(/^Frango/)).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(lines).toHaveLength(2));
    expect(lines[1]!.idempotencyKey).toBe(lines[0]!.idempotencyKey);
    expect(lines[1]!.body).toEqual(lines[0]!.body);
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  it('a 504 is an unknown outcome too: "Tentar novamente" with the same key', async () => {
    const lines = addLineEndpoint(ORDER_ID, () => problem(504, 'HTTP_504'), created(added));
    const { user } = await atOrder();
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));

    await user.click(tile(/^Bitoque/));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — Bitoque não foi confirmado.');
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(lines).toHaveLength(2));
    expect(lines[1]!.idempotencyKey).toBe(lines[0]!.idempotencyKey);
  });

  it('"Descartar" reads the order again: the discarded line may have been added', async () => {
    addLineEndpoint(ORDER_ID, () => HttpResponse.error());
    const { user, order } = await atOrder([json(ORDER), json(withLines(ORDER, [...ORDER.lines!, added]))]);
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));
    await user.click(tile(/^Bitoque/));
    await screen.findByRole('alert');
    await waitFor(() => expect(screen.queryByRole('status', { name: 'A adicionar' })).not.toBeInTheDocument());
    const before = order.length;

    await user.click(screen.getByRole('button', { name: 'Descartar' }));

    await waitFor(() => expect(order.length).toBeGreaterThan(before));
    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')).toHaveLength(3));
  });

  it('"Descartar" drops a failed intent; the next tap has a new key', async () => {
    const lines = addLineEndpoint(ORDER_ID, () => HttpResponse.error(), created(added));
    const { user } = await atOrder();
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));
    await user.click(tile(/^Bitoque/));
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: 'Descartar' }));
    await user.click(tile(/^Bitoque/));

    await waitFor(() => expect(lines).toHaveLength(2));
    expect(lines[1]!.idempotencyKey).not.toBe(lines[0]!.idempotencyKey);
  });

  it('a 409 "in progress" waits a second and resends the same key', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const lines = addLineEndpoint(ORDER_ID, () => problem(409, 'IDEMPOTENCY_IN_PROGRESS'), created(added));
    const { user } = await atOrder([json(ORDER)], { user: { advanceTimers: vi.advanceTimersByTime } });
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));

    await user.click(tile(/^Bitoque/));
    await waitFor(() => expect(lines).toHaveLength(1));
    await act(() => vi.advanceTimersByTimeAsync(1_000));

    await waitFor(() => expect(lines).toHaveLength(2));
    expect(lines[1]!.idempotencyKey).toBe(lines[0]!.idempotencyKey);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a business refusal shows its reason and keeps nothing to retry', async () => {
    addLineEndpoint(ORDER_ID, () => problem(422, 'TABLE_LOCKED'));
    const { user } = await atOrder();
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Pratos' }));

    await user.click(tile(/^Bitoque/));

    expect(await screen.findByRole('alert')).toHaveTextContent('Bitoque não foi adicionado. A mesa está bloqueada.');
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
    expect(tile(/^Frango/)).toBeEnabled();
  });

  it('an item with variants asks for one, then adds it with that variant', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user } = await atOrder();
    await ready();

    await user.click(tile(/^Água/));
    const sheet = await screen.findByRole('dialog', { name: 'Água' });
    const add = within(sheet).getByRole('button', { name: 'Adicionar' });
    expect(add).toBeDisabled();
    await user.click(within(sheet).getByRole('radio', { name: /Grande/ }));
    await user.click(add);

    await waitFor(() => expect(lines).toHaveLength(1));
    expect(lines[0]!.body).toEqual({ itemId: ITEM.water.id, variantId: VARIANT.large, quantity: '1', components: [] });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a menu asks for its choices within each group\'s minimum and maximum', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user } = await atOrder();
    await ready();
    await user.click(within(familyRail()).getByRole('button', { name: 'Menus' }));

    await user.click(tile(/^Menu do dia/));
    const sheet = await screen.findByRole('dialog', { name: 'Menu do dia' });
    const dish = within(sheet).getByRole('group', { name: 'Prato (escolha 1)' });
    const drinks = within(sheet).getByRole('group', { name: 'Bebidas (escolha 1 a 2)' });
    const add = within(sheet).getByRole('button', { name: 'Adicionar' });

    await user.click(within(dish).getByRole('checkbox', { name: 'Bitoque' }));
    expect(add).toBeDisabled(); // drinks: at least one
    await user.click(within(drinks).getByRole('checkbox', { name: 'Água Pequena' }));
    await user.click(within(drinks).getByRole('checkbox', { name: 'Bica' }));
    // the maximum reached: the third option cannot be chosen
    expect(within(drinks).getByRole('checkbox', { name: 'Sumo de laranja' })).toBeDisabled();
    // a one-choice group swaps its choice
    await user.click(within(dish).getByRole('checkbox', { name: 'Frango grelhado' }));
    expect(within(dish).getByRole('checkbox', { name: 'Bitoque' })).not.toBeChecked();
    await user.click(add);

    await waitFor(() => expect(lines).toHaveLength(1));
    expect(lines[0]!.body).toEqual({
      itemId: ITEM.menu.id,
      quantity: '1',
      components: [
        { groupCode: 'PRATO', itemId: ITEM.chicken.id },
        { groupCode: 'BEBIDA', itemId: ITEM.water.id, variantId: VARIANT.small },
        { groupCode: 'BEBIDA', itemId: ITEM.coffee.id },
      ],
    });
  });

  it('a choice sheet takes a note of at most 200 characters', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user } = await atOrder();
    await ready();

    await user.click(tile(/^Água/));
    const sheet = await screen.findByRole('dialog', { name: 'Água' });
    await user.click(within(sheet).getByRole('radio', { name: /Pequena/ }));
    const note = within(sheet).getByRole('textbox', { name: 'Nota para a cozinha (opcional)' });
    expect(note).toHaveAttribute('maxLength', '200');
    await user.type(note, 'Natural, sem gelo');
    await user.click(within(sheet).getByRole('button', { name: 'Adicionar' }));

    await waitFor(() => expect(lines).toHaveLength(1));
    expect(lines[0]!.body).toMatchObject({ variantId: VARIANT.small, notes: 'Natural, sem gelo' });
  });

  it('cancelling a choice sheet sends nothing', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user } = await atOrder();
    await ready();

    await user.click(tile(/^Água/));
    await user.click(within(await screen.findByRole('dialog', { name: 'Água' })).getByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(lines).toHaveLength(0);
  });

  it('a draft line takes a note, sent with its version, quantity and variant', async () => {
    const updates = updateLineEndpoint(ORDER_ID, LINE.coffee.id!, json({ ...LINE.coffee, notes: 'Cheio', version: 4 }));
    const { user } = await atOrder();
    await ready();

    await user.click(within(drafts()).getByRole('button', { name: 'Nota de Bica' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nota de Bica' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Nota para a cozinha (opcional)' }), 'Cheio');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(updates).toHaveLength(1));
    expect(updates[0]!.body).toEqual({ version: 3, quantity: '1.000', notes: 'Cheio' });
    expect(updates[0]!.idempotencyKey).toBeNull();
  });

  it('code entry: an item code or a barcode, then Enter, adds it (the scanner types like a keyboard)', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user } = await atOrder();
    await ready();
    const search = screen.getByRole('searchbox', { name: 'Pesquisar artigo ou código' });

    await user.type(search, 'bitoque{Enter}');
    await waitFor(() => expect(lines).toHaveLength(1));
    expect(lines[0]!.body).toMatchObject({ itemId: ITEM.steak.id });
    expect(search).toHaveValue('');

    // A barcode naming a variant adds that variant without asking.
    await user.type(search, '5601234000028{Enter}');
    await waitFor(() => expect(lines).toHaveLength(2));
    expect(lines[1]!.body).toMatchObject({ itemId: ITEM.water.id, variantId: VARIANT.large });
  });

  it('code entry of an item that needs choices opens its sheet; an unknown code says so', async () => {
    const lines = addLineEndpoint(ORDER_ID, created(added));
    const { user } = await atOrder();
    await ready();
    const search = screen.getByRole('searchbox', { name: 'Pesquisar artigo ou código' });

    await user.type(search, 'AGUA{Enter}');
    expect(await screen.findByRole('dialog', { name: 'Água' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));

    await user.type(search, '999{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('Nenhum artigo com o código 999.');
    expect(lines).toHaveLength(0);
  });

  it('changes the quantity of a draft with its version, and shows the server\'s answer', async () => {
    const updates = updateLineEndpoint(ORDER_ID, LINE.water.id!, json({ ...LINE.water, quantity: '3.000', version: 2 }));
    const { user } = await atOrder([
      json(ORDER),
      json(withLines(ORDER, [{ ...LINE.water, quantity: '3.000', version: 2, gross: { amount: '180.00', currency: 'MZN' } }, LINE.coffee, LINE.steak])),
    ]);
    await ready();

    await user.click(within(drafts()).getByRole('button', { name: 'Aumentar Água Pequena' }));

    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')[0]).toHaveTextContent('3 × Água Pequena'));
    expect(within(drafts()).getAllByRole('listitem')[0]).toHaveTextContent('180,00 MTn');
    expect(updates[0]!.body).toEqual({ version: 1, quantity: '3', variantId: VARIANT.small, notes: 'Sem gelo' });

    await user.click(within(drafts()).getByRole('button', { name: 'Diminuir Água Pequena' }));
    await waitFor(() => expect(updates).toHaveLength(2));
    expect(updates[1]!.body).toMatchObject({ version: 2, quantity: '2' });
  });

  it('decimal drafts step by exactly one: 1.3 − 1 = 0.3, 2.3 − 1 = 1.3, 0.65 + 1 = 1.65', async () => {
    const water = { ...LINE.water, quantity: '1.300' };
    const coffee = { ...LINE.coffee, quantity: '2.300' };
    const juice = { ...LINE.coffee, id: '6f1c2a9e-0000-4000-8000-000000000809', itemId: ITEM.juice.id, quantity: '0.650' };
    const sent: UpdateLineRequest[] = [];
    for (const line of [water, coffee, juice]) {
      updateLineEndpoint(ORDER_ID, line.id!, (body) => {
        sent.push(body);
        return HttpResponse.json(line);
      });
    }
    const { user } = await atOrder([json(withLines(ORDER, [water, coffee, juice, LINE.steak]))]);
    await ready();

    await user.click(within(drafts()).getByRole('button', { name: 'Diminuir Água Pequena' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    await waitFor(() => expect(within(drafts()).getByRole('button', { name: 'Diminuir Bica' })).toBeEnabled());
    await user.click(within(drafts()).getByRole('button', { name: 'Diminuir Bica' }));
    await waitFor(() => expect(sent).toHaveLength(2));
    await waitFor(() => expect(within(drafts()).getByRole('button', { name: 'Aumentar Sumo de laranja' })).toBeEnabled());
    await user.click(within(drafts()).getByRole('button', { name: 'Aumentar Sumo de laranja' }));
    await waitFor(() => expect(sent).toHaveLength(3));

    expect(sent.map((body) => body.quantity)).toEqual(['0.3', '1.3', '1.65']);
  });

  it('a draft of one cannot go below one: it is removed instead', async () => {
    await atOrder();
    await ready();

    expect(within(drafts()).getByRole('button', { name: 'Diminuir Bica' })).toBeDisabled();
  });

  it('removes a draft with its version', async () => {
    const deletes = deleteLineEndpoint(ORDER_ID, LINE.coffee.id!, noContent());
    await atOrder([json(ORDER), json(withLines(ORDER, [LINE.water, LINE.steak]))]).then(async ({ user }) => {
      await ready();
      await user.click(within(drafts()).getByRole('button', { name: 'Remover Bica' }));
    });

    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')).toHaveLength(1));
    expect(deletes[0]!.body).toEqual({ version: 3 });
  });

  it.each(['VERSION_CONFLICT', 'HTTP_409'])('a version conflict (%s) reads the order again and says the line changed', async (code) => {
    deleteLineEndpoint(ORDER_ID, LINE.coffee.id!, () => problem(409, code));
    const changed = withLines(ORDER, [LINE.water, { ...LINE.coffee, quantity: '2.000', version: 4 }, LINE.steak]);
    const { user } = await atOrder([json(ORDER), json(changed)]);
    await ready();

    await user.click(within(drafts()).getByRole('button', { name: 'Remover Bica' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A linha foi alterada noutro terminal. O pedido foi atualizado; reveja-o e tente de novo.',
    );
    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')[1]).toHaveTextContent('2 × Bica'));
  });

  it('"Sem ligação" disables every command: products, quantities, removal', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    await atOrder();
    await ready();

    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Sem ligação'));
    expect(screen.getAllByText('Sem ligação — nenhuma operação é confirmada').length).toBeGreaterThan(0);
    expect(tile(/^Bica/)).toBeDisabled();
    expect(within(drafts()).getByRole('button', { name: 'Aumentar Água Pequena' })).toBeDisabled();
    expect(within(drafts()).getByRole('button', { name: 'Remover Bica' })).toBeDisabled();
  });

  it('an ORDER change on the stream reads the open order again; its payload is never applied', async () => {
    const stream = notificationsEndpoint();
    const changed = withLines(ORDER, [...ORDER.lines!, added]);
    const { order } = await atOrder([json(ORDER), json(changed)]);
    await ready();
    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Ligado'));
    const before = order.length;

    stream.streams[0]!.change('ORDER', ORDER_ID);

    await waitFor(() => expect(order.length).toBeGreaterThan(before));
    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')).toHaveLength(3));
  });

  it('"Voltar às mesas" goes back to the table map', async () => {
    const { user, router } = await atOrder();
    await ready();

    await user.click(screen.getByRole('link', { name: 'Voltar às mesas' }));

    expect(router.state.location.pathname).toBe('/tables');
  });
});
