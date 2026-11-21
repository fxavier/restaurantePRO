import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa } from '../../test/fixtures';
import {
  commands,
  cozinha,
  INVENTORY,
  inventoryServer,
  lusitania,
  orderDraft,
  orderSubmitted,
  polvo,
  receiptDraft,
  rowsOf,
} from '../../test/inventory-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const COMPRAS = `${INVENTORY}&tab=compras`;
const BASE = '/api/v1/backoffice/inventory';

/** Every inventory command answers 200 with an empty body unless a test says otherwise. */
function acceptCommands() {
  server.use(http.post(api(`${BASE}/*`), () => HttpResponse.json({})));
}

describe('purchasing', () => {
  it('opens on the Compras tab from the link and lists the store’s purchase orders with supplier, date and state', async () => {
    inventoryServer();

    renderApp(COMPRAS);

    const table = await screen.findByRole('table', { name: 'Encomendas a fornecedores' });
    await waitFor(() =>
      expect(rowsOf(table, 4)).toEqual([
        ['OC-904', 'Lusitânia Pescados', '21/11/2026', 'Submetida · Parcial'],
        ['OC-905', 'Lusitânia Pescados', '21/11/2026', 'Rascunho'],
      ]),
    );
  });

  it('keeps the chosen tab in the address, so a reload shows it again', async () => {
    inventoryServer();
    const { user, currentUrl } = renderApp(INVENTORY);

    await user.click(await screen.findByRole('tab', { name: 'Compras' }));

    expect(await screen.findByRole('table', { name: 'Encomendas a fornecedores' })).toBeInTheDocument();
    expect(currentUrl()).toBe(`/inventory?storeId=${baixa.id}&tab=compras`);
  });

  it('submits a draft order under its own Idempotency-Key', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(COMPRAS);

    await user.click(await screen.findByRole('button', { name: 'Submeter OC-905' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ method: 'POST', path: `${BASE}/purchase-orders/${orderDraft.id}/submit` });
    expect(commands(state)[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('closes a submitted order only after confirming it', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(COMPRAS);

    await user.click(await screen.findByRole('button', { name: 'Fechar OC-904' }));
    const confirm = await screen.findByRole('alertdialog', { name: 'Fechar a encomenda OC-904?' });
    expect(commands(state)).toEqual([]);
    await user.click(within(confirm).getByRole('button', { name: 'Fechar encomenda' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ path: `${BASE}/purchase-orders/${orderSubmitted.id}/close` });
  });

  it('creates a purchase order with its lines as decimal strings', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(COMPRAS);

    await user.click(await screen.findByRole('button', { name: 'Nova encomenda' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova encomenda' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Número' }), 'OC-906');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Fornecedor' }), lusitania.name);
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Armazém' }), cozinha.name);
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Artigo 1' }), polvo.name);
    await user.type(within(dialog).getByRole('textbox', { name: 'Quantidade 1' }), '12,5');
    await user.type(within(dialog).getByRole('textbox', { name: 'Custo unitário 1' }), '9,80');
    await user.click(within(dialog).getByRole('button', { name: 'Criar encomenda' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/purchase-orders`,
      body: {
        number: 'OC-906',
        supplierId: lusitania.id,
        warehouseId: cozinha.id,
        lines: [{ stockItemId: polvo.id, quantity: '12.5', unitCost: '9.80' }],
      },
    });
  });

  it("receives a submitted order's outstanding quantities as a draft receipt", async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(COMPRAS);

    await user.click(await screen.findByRole('button', { name: 'Receber OC-904' }));
    const dialog = await screen.findByRole('dialog', { name: 'Receber a encomenda OC-904' });
    expect(await within(dialog).findByRole('textbox', { name: `Recebido de ${polvo.name}` })).toHaveValue('20');
    await user.type(within(dialog).getByRole('textbox', { name: 'Guia do fornecedor' }), 'GR-900');
    await user.click(within(dialog).getByRole('button', { name: 'Registar receção' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/receipts`,
      body: {
        warehouseId: cozinha.id,
        supplierId: lusitania.id,
        purchaseOrderId: orderSubmitted.id,
        supplierDocumentRef: 'GR-900',
        lines: [{ purchaseOrderLineId: orderSubmitted.lines![0]!.id, stockItemId: polvo.id, packQuantity: '20' }],
      },
    });
  });

  it('lists the receipts and confirms a draft one', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(COMPRAS);

    const table = await screen.findByRole('table', { name: 'Receções de mercadoria' });
    await waitFor(() => expect(rowsOf(table, 3)).toEqual([['GR-882', 'Lusitânia Pescados', 'Rascunho']]));
    await user.click(within(table).getByRole('button', { name: 'Confirmar receção GR-882' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ path: `${BASE}/receipts/${receiptDraft.id}/confirm` });
  });

  it('lists the suppliers and adds one', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(COMPRAS);

    const table = await screen.findByRole('table', { name: 'Fornecedores' });
    await waitFor(() => expect(rowsOf(table, 3)).toEqual([['SUP-01', 'Lusitânia Pescados', '400123456']]));
    await user.click(screen.getByRole('button', { name: 'Novo fornecedor' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo fornecedor' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Código' }), 'SUP-02');
    await user.type(within(dialog).getByRole('textbox', { name: 'Nome' }), 'Hortícolas do Vale');
    await user.click(within(dialog).getByRole('button', { name: 'Criar fornecedor' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ path: `${BASE}/suppliers`, body: { code: 'SUP-02', name: 'Hortícolas do Vale' } });
  });

  it('hides the purchasing commands from a user without purchasing or receiving permissions', async () => {
    inventoryServer({ session: admin });

    renderApp(COMPRAS);

    await screen.findByRole('table', { name: 'Encomendas a fornecedores' });
    await screen.findByText('OC-904');
    for (const name of ['Nova encomenda', 'Novo fornecedor', 'Submeter OC-905', 'Receber OC-904']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });
});
