import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { api } from '../../test/fixtures';
import { azeite, commands, INVENTORY, inventoryServer, rowsOf, transferDraft } from '../../test/inventory-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const BASE = '/api/v1/backoffice/inventory';
const OTHER_STORE = 'a9000000-0000-4000-8000-000000000009';

describe('transfers', () => {
  it('lists the store’s transfers between warehouses and ships a draft one', async () => {
    const state = inventoryServer();
    server.use(http.post(api(`${BASE}/*`), () => HttpResponse.json({})));
    const { user } = renderApp(`${INVENTORY}&tab=transferencias`);

    const table = await screen.findByRole('table', { name: 'Transferências' });
    await waitFor(() => expect(rowsOf(table, 3)).toEqual([['Cozinha', 'Bar', 'Rascunho']]));
    await user.click(within(table).getByRole('button', { name: 'Enviar transferência Cozinha → Bar' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ path: `${BASE}/transfers/${transferDraft.id}/ship` });
  });

  it('receives a shipped transfer as shipped: each received quantity starts at what the server says was shipped', async () => {
    const state = inventoryServer({ transfers: [{ ...transferDraft, status: 'SHIPPED' }] });
    server.use(http.post(api(`${BASE}/*`), () => HttpResponse.json({})));
    const { user } = renderApp(`${INVENTORY}&tab=transferencias`);

    await user.click(await screen.findByRole('button', { name: 'Receber transferência Cozinha → Bar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Receber a transferência Cozinha → Bar' });
    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: `Recebido de ${azeite.name}` })).toHaveValue('5'));
    expect(within(dialog).getByText('Enviado 5 L')).toBeInTheDocument();
    expect(within(dialog).queryByRole('textbox', { name: 'Motivo da diferença' })).not.toBeInTheDocument();
    expect(commands(state)).toEqual([]);
    await user.click(within(dialog).getByRole('button', { name: 'Registar receção' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/transfers/${transferDraft.id}/receive`,
      body: { lines: [{ stockItemId: azeite.id, quantityReceived: '5' }] },
      idempotencyKey: expect.any(String),
    });
    expect(commands(state)[0]?.body).not.toHaveProperty('reason');
  });

  it('asks why a transfer arrived short and sends the reason with what arrived', async () => {
    const state = inventoryServer({ transfers: [{ ...transferDraft, status: 'SHIPPED' }] });
    server.use(http.post(api(`${BASE}/*`), () => HttpResponse.json({})));
    const { user } = renderApp(`${INVENTORY}&tab=transferencias`);

    await user.click(await screen.findByRole('button', { name: 'Receber transferência Cozinha → Bar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Receber a transferência Cozinha → Bar' });
    const received = await within(dialog).findByRole('textbox', { name: `Recebido de ${azeite.name}` });
    await user.clear(received);
    await user.type(received, '4,5');
    const reason = await within(dialog).findByRole('textbox', { name: 'Motivo da diferença' });
    await user.click(within(dialog).getByRole('button', { name: 'Registar receção' }));

    expect(await within(dialog).findByText('Indique o motivo da diferença.')).toBeInTheDocument();
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(commands(state)).toEqual([]);
    await user.type(reason, 'Garrafa partida no transporte');
    await user.click(within(dialog).getByRole('button', { name: 'Registar receção' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/transfers/${transferDraft.id}/receive`,
      body: { lines: [{ stockItemId: azeite.id, quantityReceived: '4.5' }], reason: 'Garrafa partida no transporte' },
    });
  });

  it('starts each received quantity at the shipped one as typed in pt, without the server’s trailing zeros', async () => {
    inventoryServer({
      transfers: [{ ...transferDraft, status: 'SHIPPED', lines: [{ stockItemId: azeite.id, quantityShipped: '2.500000', unitCost: '7.50' }] }],
    });
    const { user } = renderApp(`${INVENTORY}&tab=transferencias`);

    await user.click(await screen.findByRole('button', { name: 'Receber transferência Cozinha → Bar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Receber a transferência Cozinha → Bar' });

    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: `Recebido de ${azeite.name}` })).toHaveValue('2,5'));
    expect(within(dialog).queryByRole('textbox', { name: 'Motivo da diferença' })).not.toBeInTheDocument();
  });

  it('refuses on the form a received quantity above what was shipped or below zero', async () => {
    const state = inventoryServer({ transfers: [{ ...transferDraft, status: 'SHIPPED' }] });
    const { user } = renderApp(`${INVENTORY}&tab=transferencias`);

    await user.click(await screen.findByRole('button', { name: 'Receber transferência Cozinha → Bar' }));
    const dialog = await screen.findByRole('dialog', { name: 'Receber a transferência Cozinha → Bar' });
    const received = await within(dialog).findByRole('textbox', { name: `Recebido de ${azeite.name}` });
    await user.clear(received);
    await user.type(received, '5,5');
    await user.click(within(dialog).getByRole('button', { name: 'Registar receção' }));

    expect(await within(dialog).findByText('Não pode receber mais do que o enviado.')).toBeInTheDocument();
    await user.clear(received);
    await user.type(received, '-1');
    await user.click(within(dialog).getByRole('button', { name: 'Registar receção' }));
    expect(await within(dialog).findByText('Indique uma quantidade de zero ou mais.')).toBeInTheDocument();
    expect(commands(state)).toEqual([]);
  });

  it('offers Enviar only in the source store and Receber only in the destination store', async () => {
    inventoryServer({
      transfers: [
        { ...transferDraft, id: 'b1000000-0000-4000-8000-000000000002', toStoreId: OTHER_STORE, status: 'SHIPPED' },
        { ...transferDraft, id: 'b1000000-0000-4000-8000-000000000003', fromStoreId: OTHER_STORE, status: 'DRAFT' },
      ],
    });

    renderApp(`${INVENTORY}&tab=transferencias`);

    const table = await screen.findByRole('table', { name: 'Transferências' });
    await waitFor(() => expect(rowsOf(table, 3)).toHaveLength(2));
    expect(within(table).queryByRole('button', { name: /^Receber/ })).not.toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: /^Enviar/ })).not.toBeInTheDocument();
  });
});

describe('reports', () => {
  it("shows the store's valuation with the server's total, and what is below minimum", async () => {
    inventoryServer();

    renderApp(`${INVENTORY}&tab=relatorios`);

    const valuation = await screen.findByRole('table', { name: 'Valorização do stock' });
    await waitFor(() => expect(rowsOf(valuation, 4)).toEqual([['COZ', 'Polvo fresco', '3,5 kg', '34,30 MTn']]));
    await waitFor(() =>
      expect(screen.getByText((_, element) => element?.tagName === 'DIV' && /^Total: 14\s820,50\sMTn$/.test(element.textContent))).toBeInTheDocument(),
    );
    const low = screen.getByRole('table', { name: 'Artigos abaixo do mínimo' });
    await waitFor(() => expect(rowsOf(low, 5)).toEqual([['COZ', 'Polvo fresco', '3,5 kg', '5 kg', '1,5 kg']]));
  });
});
