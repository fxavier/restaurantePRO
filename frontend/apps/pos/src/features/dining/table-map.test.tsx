import { formatMoney } from '@restaurantpro/ui';
import { act, screen, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { problem, SESSION, tableMap, tablesEndpoint, token, tokenEndpoint } from '../../test/server';
import { id, MAP, openFromMap } from '../../test/table-map';
import { catalogEndpoint, json, ORDER, ORDER_ID, orderEndpoint } from '../../test/order';
import { renderPos } from '../../test/render-pos';

async function atTables(...answers: (() => Response | Promise<Response>)[]) {
  tokenEndpoint(token('jwt-1'));
  const requests = tablesEndpoint(...(answers.length ? answers : [tableMap(MAP)]));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  return { ...rendered, requests };
}

/** MAP with E1, on the terrace, occupied (its order is ORDER_ID, moved there for the test). */
const E1_OCCUPIED = {
  ...MAP,
  areas: MAP.areas!.map((area) => ({
    ...area,
    tables: area.tables!.map((t) => (t.code === 'E1' ? { ...t, status: 'OCCUPIED' as const, guests: 2, openOrderId: ORDER_ID } : t)),
  })),
};

const tile = (name: RegExp) => screen.getByRole('button', { name });

/** The terminal's clock at 14:00 in the store (12:00Z): M2 opened 1 h 55 min ago, M3 20 min ago. */
function at14h() {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-18T12:00:00Z'));
}

describe('table map', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a labelled loading state while the map is read, with the terminal token and the POS session', async () => {
    const { requests } = await atTables(async () => {
      await delay('infinite');
      return HttpResponse.json(MAP);
    });

    expect(await screen.findByRole('status', { name: 'A carregar as mesas…' })).toBeInTheDocument();
    expect(requests[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: SESSION.posSession });
    expect(requests[0]!.url).toMatch(/\/api\/v1\/pos\/tables$/);
  });

  it('shows one tab per area, the first selected, with its tables', async () => {
    await atTables();

    // Each tab shows its table count too, hidden from assistive technology (the summary says it).
    const tabs = await screen.findAllByRole('tab');
    expect(tabs).toHaveLength(2);
    expect(tabs[0]).toHaveAccessibleName('Sala');
    expect(tabs[0]).toHaveTextContent('Sala4');
    expect(tabs[1]).toHaveAccessibleName('Esplanada');
    expect(screen.getByRole('tab', { name: 'Sala' })).toHaveAttribute('aria-selected', 'true');
    const panel = screen.getByRole('tabpanel', { name: 'Sala' });
    expect(within(panel).getAllByRole('button').map((button) => button.textContent)).toEqual([
      expect.stringContaining('M1'),
      expect.stringContaining('M2'),
      expect.stringContaining('M3'),
      expect.stringContaining('M4'),
    ]);
  });

  it('another area shows its own tables', async () => {
    const { user } = await atTables();

    await user.click(await screen.findByRole('tab', { name: 'Esplanada' }));

    const panel = screen.getByRole('tabpanel', { name: 'Esplanada' });
    expect(within(panel).getByRole('button', { name: /E1/ })).toHaveAccessibleName('Mesa E1 Livre 2 lugares');
    expect(screen.queryByRole('button', { name: /M1/ })).not.toBeInTheDocument();
  });

  it('labels a free table "Livre" with its seats', async () => {
    await atTables();

    expect(await screen.findByRole('button', { name: /^Mesa M1 / })).toHaveAccessibleName('Mesa M1 Livre 4 lugares');
  });

  it('labels an occupied table "Ocupada" with its guests and the minutes since it opened', async () => {
    at14h();
    await atTables();

    expect(await screen.findByRole('button', { name: /^Mesa M2 / })).toHaveAccessibleName(
      'Mesa M2 Ocupada 3 pessoas · 1 h 55 min',
    );
  });

  it('marks locked, bill requested (subtotal printed) and reserved with a label each, in that order', async () => {
    at14h();
    await atTables();

    const m3 = await screen.findByRole('button', { name: /^Mesa M3 / });
    expect(m3).toHaveAccessibleName('Mesa M3 Ocupada 1 pessoa · 20 min Bloqueada Conta pedida Reservada 20:30');
    expect(tile(/^Mesa M4 /)).toHaveAccessibleName('Mesa M4 Livre 1 lugar Reservada 21:00');
  });

  it('sums up the store by state, each table once (locked before occupied)', async () => {
    await atTables();

    const summary = await screen.findByRole('list', { name: 'Resumo das mesas' });
    expect(within(summary).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Livres 3',
      'Ocupadas 1',
      'Conta pedida 0',
      'Bloqueadas 1',
    ]);
  });

  it('a tap selects a table for the detail panel, which opens a free one', async () => {
    const { user } = await atTables();
    const panel = await screen.findByRole('complementary', { name: 'Detalhe da mesa' });
    expect(panel).toHaveTextContent('Toque numa mesa para ver o detalhe.');

    await user.click(tile(/^Mesa M1 /));

    expect(tile(/^Mesa M1 /)).toHaveAttribute('aria-pressed', 'true');
    expect(within(panel).getByRole('heading', { name: 'Mesa M1' })).toHaveFocus();
    expect(panel).toHaveTextContent('Sala · 4 lugares');
    await user.click(within(panel).getByRole('button', { name: 'Abrir mesa' }));
    expect(await screen.findByRole('dialog', { name: 'Abrir mesa M1' })).toBeInTheDocument();
  });

  it("the panel shows an occupied table's rounds and the server's total, and goes to its order", async () => {
    catalogEndpoint();
    orderEndpoint(ORDER_ID, json(ORDER));
    const { user, router } = await atTables();

    await user.click(await screen.findByRole('button', { name: /^Mesa M2 / }));

    const panel = screen.getByRole('complementary', { name: 'Detalhe da mesa' });
    expect(within(panel).getByText('Sala · 3 pessoas')).toBeInTheDocument();
    expect(within(panel).getByText('Abertura').nextSibling).toHaveTextContent('12:05');
    expect(await within(panel).findByRole('list', { name: 'Ronda 1, 12:10' })).toHaveTextContent('Bitoque');
    expect(within(panel).getByRole('group', { name: 'Total' })).toHaveTextContent(
      formatMoney({ amount: '1350.00', currency: 'MZN' }).replace(/\s/g, ' '),
    );
    await user.click(within(panel).getByRole('button', { name: 'Adicionar artigos' }));
    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
  });

  it('"Mesa ou nº" shows only the area\'s tables whose code has the typed text, and says when none has', async () => {
    const { user } = await atTables();

    await user.type(await screen.findByRole('searchbox', { name: 'Procurar mesa' }), 'm3');

    const panel = screen.getByRole('tabpanel', { name: 'Sala' });
    expect(within(panel).getAllByRole('button').map((button) => button.textContent)).toEqual([expect.stringContaining('M3')]);
    await user.clear(screen.getByRole('searchbox', { name: 'Procurar mesa' }));
    await user.type(screen.getByRole('searchbox', { name: 'Procurar mesa' }), 'X9');
    expect(within(panel).getByText('Nenhuma mesa com «X9» nesta área.')).toBeInTheDocument();
    await user.clear(screen.getByRole('searchbox', { name: 'Procurar mesa' }));
    expect(within(panel).getAllByRole('button')).toHaveLength(4);
  });

  it('names the store in the status bar', async () => {
    await atTables();

    expect(await screen.findByRole('banner')).toHaveTextContent('Loja Baixa');
  });

  it('an occupied tile opens its order by the id the map gives (no search), and "Voltar às mesas" comes back', async () => {
    catalogEndpoint();
    orderEndpoint(ORDER_ID, json(ORDER));
    const { user, router } = await atTables();

    await openFromMap(user, /^Mesa M2 /);

    expect(await screen.findByRole('heading', { name: 'Mesa M2' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
    await user.click(screen.getByRole('link', { name: 'Voltar às mesas' }));
    expect(await screen.findByRole('button', { name: /^Mesa M2 / })).toBeInTheDocument();
  });

  it('returning from a table keeps the area that was open', async () => {
    const e1 = { ...ORDER, tableId: id(10), tableCode: 'E1' };
    catalogEndpoint();
    orderEndpoint(ORDER_ID, json(e1));
    const { user } = await atTables(tableMap(E1_OCCUPIED));

    await user.click(await screen.findByRole('tab', { name: 'Esplanada' }));
    await openFromMap(user, /^Mesa E1 /);
    await screen.findByRole('heading', { name: 'Mesa E1' });
    await user.click(screen.getByRole('link', { name: 'Voltar às mesas' }));

    expect(await screen.findByRole('tab', { name: 'Esplanada' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: /^Mesa E1 / })).toBeInTheDocument();
  });

  it('an unknown area in the URL falls back to the first area', async () => {
    tokenEndpoint(token('jwt-1'));
    tablesEndpoint(tableMap(MAP));
    const { router } = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
    // The login lands on the plain map; the unknown area comes after, as from an old link.
    await act(() => router.navigate('/tables?area=gone'));

    expect(await screen.findByRole('tab', { name: 'Sala' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: /^Mesa M1 / })).toBeInTheDocument();
  });

  it('says so when the store has no tables', async () => {
    await atTables(tableMap({ storeId: SESSION.storeId, areas: [] }));

    expect(await screen.findByText('Esta loja ainda não tem mesas. Um gestor cria as mesas no BackOffice.')).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });

  it('says so when an area has no tables', async () => {
    await atTables(tableMap({ storeId: SESSION.storeId, areas: [{ id: id(903), code: 'BAR', name: 'Bar', tables: [] }] }));

    expect(await screen.findByText('Sem mesas nesta área.')).toBeInTheDocument();
  });

  it('shows an error with the server code and "Tentar novamente", which reads the map again', async () => {
    const { user, requests } = await atTables(() => problem(500, 'INTERNAL'), tableMap(MAP));

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as mesas. Erro inesperado (INTERNAL).');
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('button', { name: /^Mesa M1 / })).toBeInTheDocument();
    expect(requests).toHaveLength(2);
  });

  it('shows "Sem ligação" when the backend does not answer', async () => {
    await atTables(() => HttpResponse.error());

    // Two retries on a network error (FC §5) come first.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Sem ligação — não foi possível carregar as mesas.',
    );
    expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument();
  });

  it('a 503 is "Sem ligação" too', async () => {
    await atTables(() => problem(503, 'UNAVAILABLE'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível carregar as mesas.');
  });

  it('keeps the status bar: the employee and "Trocar empregado"', async () => {
    await atTables();

    expect(await screen.findByText(SESSION.name)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
  });
});
