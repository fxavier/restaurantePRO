import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, problem } from '../../test/fixtures';
import { azeite, bar, commands, cozinha, INVENTORY, inventoryServer, polvo, rowsOf } from '../../test/inventory-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

describe('stock', () => {
  it("lists the first warehouse's stock with its minimum, average cost and value in the store's currency", async () => {
    inventoryServer();

    renderApp(INVENTORY);

    const table = await screen.findByRole('table', { name: 'Stock do armazém' });
    expect(rowsOf(table, 6)).toEqual([
      ['Polvo frescoSTK-POL-001', '3,5 kg', '5 kg', '9,80 MTn', '34,30 MTn', 'Abaixo do mínimo'],
      ['Azeite virgem extraSTK-AZT-004', '42 L', '10 L', '7,50 MTn', '315,00 MTn', 'Normal'],
    ]);
  });

  it("sums up the store: the server's total value and how many items are below their minimum", async () => {
    inventoryServer();

    renderApp(INVENTORY);

    const value = await screen.findByRole('group', { name: 'Valor total em stock' });
    await waitFor(() => expect(value).toHaveTextContent(/14\s820,50\sMTn/));
    const low = screen.getByRole('group', { name: 'Abaixo do mínimo' });
    await waitFor(() => expect(low).toHaveTextContent('1 artigo'));
  });

  it('shows another warehouse, and only what is below its minimum when asked', async () => {
    const state = inventoryServer();
    const { user } = renderApp(INVENTORY);
    await screen.findByRole('table', { name: 'Stock do armazém' });

    await user.click(screen.getByRole('checkbox', { name: 'Só abaixo do mínimo' }));
    await waitFor(() => expect(screen.queryByText(azeite.name)).not.toBeInTheDocument());
    expect(screen.getByText(polvo.name)).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Armazém' }), bar.name);
    expect(await screen.findByText('Sem stock neste armazém.')).toBeInTheDocument();
    expect(state.requests.some((r) => r.path.endsWith('/stock') && r.search.includes(`warehouseId=${bar.id}`))).toBe(true);
  });

  it('records waste of an item in the warehouse, under one Idempotency-Key, and rereads the stock', async () => {
    const state = inventoryServer();
    let reads = 0;
    server.use(
      http.post(api('/api/v1/backoffice/inventory/movements/waste'), () =>
        HttpResponse.json({ sourceType: 'MANUAL', sourceId: 'x', movements: [] }, { status: 201 }),
      ),
    );
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'GET' && new URL(request.url).pathname.endsWith('/inventory/stock')) reads += 1;
    });
    const { user } = renderApp(INVENTORY);
    await screen.findByRole('table', { name: 'Stock do armazém' });
    const before = reads;

    await user.click(screen.getByRole('button', { name: 'Registar quebra' }));
    const dialog = await screen.findByRole('dialog', { name: 'Registar quebra' });
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Artigo' }), polvo.name);
    await user.type(within(dialog).getByRole('textbox', { name: 'Quantidade' }), '0,5');
    await user.type(within(dialog).getByRole('textbox', { name: 'Motivo' }), 'Estragado');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const [sent] = commands(state);
    expect(sent).toMatchObject({
      method: 'POST',
      path: '/api/v1/backoffice/inventory/movements/waste',
      body: { warehouseId: cozinha.id, reason: 'Estragado', lines: [{ stockItemId: polvo.id, quantity: '0.5' }] },
    });
    expect(sent!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(reads).toBeGreaterThan(before));
  });

  it('adjusts a balance with a signed quantity, and keeps a server field error on its field', async () => {
    const state = inventoryServer();
    server.use(
      http.post(api('/api/v1/backoffice/inventory/movements/manual-adjustments'), () =>
        problem(400, 'VALIDATION_FAILED', 'Invalid', { errors: [{ field: 'lines[0].unitCost', message: 'Indique o custo.' }] }),
      ),
    );
    const { user } = renderApp(INVENTORY);
    await screen.findByRole('table', { name: 'Stock do armazém' });

    await user.click(screen.getByRole('button', { name: 'Ajustar stock' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar stock' });
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Artigo' }), azeite.name);
    await user.type(within(dialog).getByRole('textbox', { name: 'Quantidade' }), '-2');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));

    expect(await within(dialog).findByText('Indique o custo.')).toBeInTheDocument();
    expect(commands(state)[0]).toMatchObject({
      path: '/api/v1/backoffice/inventory/movements/manual-adjustments',
      body: { warehouseId: cozinha.id, lines: [{ stockItemId: azeite.id, quantity: '-2' }] },
    });
  });

  it('refuses a quantity that is not a number before sending anything', async () => {
    const state = inventoryServer();
    const { user } = renderApp(INVENTORY);
    await screen.findByRole('table', { name: 'Stock do armazém' });

    await user.click(screen.getByRole('button', { name: 'Registar quebra' }));
    const dialog = await screen.findByRole('dialog', { name: 'Registar quebra' });
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Artigo' }), polvo.name);
    await user.type(within(dialog).getByRole('textbox', { name: 'Quantidade' }), 'muito');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));

    expect(await within(dialog).findByText('Indique uma quantidade, por exemplo 2,5.')).toBeInTheDocument();
    expect(commands(state)).toEqual([]);
  });

  it('hides the stock commands from a user who may only look', async () => {
    inventoryServer({ session: admin });

    renderApp(INVENTORY);

    await screen.findByRole('table', { name: 'Stock do armazém' });
    expect(screen.queryByRole('button', { name: 'Registar quebra' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ajustar stock' })).not.toBeInTheDocument();
  });

  it('says the stock is unavailable without a connection, and reads it again on retry', async () => {
    inventoryServer();
    let fail = true;
    server.use(
      http.get(api('/api/v1/backoffice/inventory/stock'), () => (fail ? problem(503, 'UPSTREAM_UNAVAILABLE') : undefined)),
    );
    const { user } = renderApp(INVENTORY);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('table', { name: 'Stock do armazém' })).toBeInTheDocument();
  });
});
