import { screen, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem, SESSION, tableMap, tablesEndpoint, token, tokenEndpoint } from '../../test/server';
import { id, MAP } from '../../test/table-map';
import { catalogEndpoint, json, openOrdersEndpoint, ORDER, ORDER_ID, orderEndpoint } from '../../test/order';
import { renderPos } from '../../test/render-pos';

async function atTables(...answers: (() => Response | Promise<Response>)[]) {
  tokenEndpoint(token('jwt-1'));
  const requests = tablesEndpoint(...(answers.length ? answers : [tableMap(MAP)]));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  return { ...rendered, requests };
}

/** MAP with E1, on the terrace, occupied. */
const E1_OCCUPIED = {
  ...MAP,
  areas: MAP.areas!.map((area) => ({
    ...area,
    tables: area.tables!.map((t) => (t.code === 'E1' ? { ...t, status: 'OCCUPIED' as const, guests: 2 } : t)),
  })),
};

const tile = (name: RegExp) => screen.getByRole('button', { name });

describe('table map', () => {
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

    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Sala', 'Esplanada']);
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

  it('labels an occupied table "Ocupada" with its guests and the time it opened, in the store time', async () => {
    await atTables();

    expect(await screen.findByRole('button', { name: /^Mesa M2 / })).toHaveAccessibleName(
      'Mesa M2 Ocupada 3 pessoas desde 12:05',
    );
  });

  it('marks locked, subtotal printed and reserved with a label each, in that order', async () => {
    await atTables();

    const m3 = await screen.findByRole('button', { name: /^Mesa M3 / });
    expect(m3).toHaveAccessibleName('Mesa M3 Ocupada 1 pessoa desde 13:40 Bloqueada Subtotal Reservada 20:30');
    expect(tile(/^Mesa M4 /)).toHaveAccessibleName('Mesa M4 Livre 1 lugar Reservada 21:00');
  });

  it('an occupied tile opens its order, and "Voltar às mesas" comes back to the map', async () => {
    catalogEndpoint();
    openOrdersEndpoint([ORDER]);
    orderEndpoint(ORDER_ID, json(ORDER));
    const { user, router } = await atTables();

    await user.click(await screen.findByRole('button', { name: /^Mesa M2 / }));

    expect(await screen.findByRole('heading', { name: 'Mesa M2' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
    await user.click(screen.getByRole('link', { name: 'Voltar às mesas' }));
    expect(await screen.findByRole('button', { name: /^Mesa M2 / })).toBeInTheDocument();
  });

  it('returning from a table keeps the area that was open', async () => {
    const e1 = { ...ORDER, tableId: id(10), tableCode: 'E1' };
    catalogEndpoint();
    openOrdersEndpoint([e1]);
    orderEndpoint(ORDER_ID, json(e1));
    const { user } = await atTables(tableMap(E1_OCCUPIED));

    await user.click(await screen.findByRole('tab', { name: 'Esplanada' }));
    await user.click(screen.getByRole('button', { name: /^Mesa E1 / }));
    await screen.findByRole('heading', { name: 'Mesa E1' });
    await user.click(screen.getByRole('link', { name: 'Voltar às mesas' }));

    expect(await screen.findByRole('tab', { name: 'Esplanada' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: /^Mesa E1 / })).toBeInTheDocument();
  });

  it('an unknown area in the URL falls back to the first area', async () => {
    tokenEndpoint(token('jwt-1'));
    tablesEndpoint(tableMap(MAP));
    await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables?area=gone' });

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
    expect(await screen.findByRole('alert', {}, { timeout: 6_000 })).toHaveTextContent(
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
