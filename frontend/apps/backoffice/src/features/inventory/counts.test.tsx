import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { api, baixa } from '../../test/fixtures';
import { azeite, commands, cozinha, INVENTORY, inventoryServer, polvo } from '../../test/inventory-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';
import type { CountView } from './types';

const BASE = '/api/v1/backoffice/inventory';
const COUNT_ID = '99000000-0000-4000-8000-000000000001';

/** A count as the server holds it, moved along by the commands the screen sends. */
function countServer() {
  const count: CountView = {
    id: COUNT_ID,
    storeId: baixa.id,
    warehouseId: cozinha.id,
    kind: 'REGULAR',
    scope: 'ALL',
    status: 'DRAFT',
    createdAt: '2026-11-21T08:00:00Z',
    lines: [],
    version: 0,
  };
  const linesAtStart = [
    { id: 'l1', stockItemId: polvo.id, unit: 'kg' },
    { id: 'l2', stockItemId: azeite.id, unit: 'L' },
  ];
  server.use(
    http.post(api(`${BASE}/counts`), () => HttpResponse.json(count)),
    http.get(api(`${BASE}/counts/${COUNT_ID}`), () => HttpResponse.json(count)),
    http.post(api(`${BASE}/counts/${COUNT_ID}/start`), () => {
      Object.assign(count, { status: 'IN_PROGRESS', lines: linesAtStart });
      return HttpResponse.json(count);
    }),
    http.put(api(`${BASE}/counts/${COUNT_ID}/lines/:stockItemId`), async ({ params, request }) => {
      const { countedQuantity } = (await request.json()) as { countedQuantity: string };
      count.lines = count.lines!.map((line) => (line.stockItemId === params.stockItemId ? { ...line, countedQuantity } : line));
      return HttpResponse.json(count);
    }),
    http.post(api(`${BASE}/counts/${COUNT_ID}/submit`), () => {
      Object.assign(count, {
        status: 'SUBMITTED',
        lines: count.lines!.map((line) => ({ ...line, expectedQuantity: '3.500', difference: '-0.500' })),
      });
      return HttpResponse.json(count);
    }),
    http.post(api(`${BASE}/counts/${COUNT_ID}/approve`), () => {
      count.status = 'APPROVED';
      return HttpResponse.json(count);
    }),
  );
  return count;
}

describe('stock counts', () => {
  it('creates a blind count of a warehouse, starts it, records what was counted, submits and approves it', async () => {
    const state = inventoryServer();
    countServer();
    const { user, currentUrl } = renderApp(`${INVENTORY}&tab=contagens`);

    const warehouse = await screen.findByRole('combobox', { name: 'Armazém a contar' });
    await within(warehouse).findByRole('option', { name: cozinha.name });
    await user.selectOptions(warehouse, cozinha.name);
    await user.click(screen.getByRole('button', { name: 'Nova contagem' }));
    await waitFor(() => expect(currentUrl()).toContain(`count=${COUNT_ID}`));
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/counts`,
      body: { warehouseId: cozinha.id, kind: 'REGULAR', scope: 'ALL' },
    });

    await user.click(await screen.findByRole('button', { name: 'Iniciar contagem' }));
    const field = await screen.findByRole('textbox', { name: `Contado de ${polvo.name}` });
    expect(screen.queryByText('3,5 kg')).not.toBeInTheDocument();
    await user.type(field, '3');
    await user.click(screen.getByRole('button', { name: `Registar ${polvo.name}` }));
    await waitFor(() =>
      expect(commands(state).some((c) => c.method === 'PUT' && c.path.endsWith(`/lines/${polvo.id}`))).toBe(true),
    );
    expect(commands(state).find((c) => c.method === 'PUT')!.body).toEqual({ countedQuantity: '3' });

    await user.click(screen.getByRole('button', { name: 'Submeter contagem' }));
    const lines = await screen.findByRole('table', { name: 'Linhas da contagem' });
    await waitFor(() => expect(within(lines).getAllByText('-0,5 kg')).not.toHaveLength(0));

    await user.click(screen.getByRole('button', { name: 'Aprovar contagem' }));
    expect(await screen.findByText('Aprovada')).toBeInTheDocument();
    const posted = commands(state).filter((c) => c.method === 'POST').map((c) => c.path.split('/').pop());
    expect(posted).toEqual(['counts', 'start', 'submit', 'approve']);
    expect(new Set(commands(state).filter((c) => c.method === 'POST').map((c) => c.idempotencyKey)).size).toBe(4);
  });

  it('explains that a warehouse already has a live count', async () => {
    inventoryServer();
    server.use(
      http.post(api(`${BASE}/counts`), () =>
        HttpResponse.json({ status: 409, code: 'LIVE_COUNT_EXISTS', title: 'Live count' }, {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    );
    const { user } = renderApp(`${INVENTORY}&tab=contagens`);

    const warehouse = await screen.findByRole('combobox', { name: 'Armazém a contar' });
    await within(warehouse).findByRole('option', { name: cozinha.name });
    await user.selectOptions(warehouse, cozinha.name);
    await user.click(screen.getByRole('button', { name: 'Nova contagem' }));

    expect(await screen.findByText('Este armazém já tem uma contagem em curso.')).toBeInTheDocument();
  });
});
