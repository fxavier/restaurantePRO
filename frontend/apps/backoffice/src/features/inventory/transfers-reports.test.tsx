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

  it('receives a shipped transfer with the quantities the server says were shipped, only after confirming them', async () => {
    const state = inventoryServer({ transfers: [{ ...transferDraft, status: 'SHIPPED' }] });
    server.use(http.post(api(`${BASE}/*`), () => HttpResponse.json({})));
    const { user } = renderApp(`${INVENTORY}&tab=transferencias`);

    await user.click(await screen.findByRole('button', { name: 'Receber transferência Cozinha → Bar' }));
    const confirm = await screen.findByRole('alertdialog', { name: 'Receber a transferência Cozinha → Bar?' });
    await waitFor(() => expect(confirm).toHaveTextContent(`5 L de ${azeite.name}`));
    expect(commands(state)).toEqual([]);
    await user.click(within(confirm).getByRole('button', { name: 'Receber transferência' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/transfers/${transferDraft.id}/receive`,
      body: { lines: [{ stockItemId: azeite.id, quantityReceived: '5' }] },
    });
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
