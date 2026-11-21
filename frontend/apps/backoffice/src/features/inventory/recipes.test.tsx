import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api } from '../../test/fixtures';
import { commands, INVENTORY, inventoryServer, polvo, polvoLagareiro, rowsOf } from '../../test/inventory-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const RECEITAS = `${INVENTORY}&tab=receitas`;
const BASE = '/api/v1/backoffice/inventory';

function acceptCommands() {
  server.use(http.post(api(`${BASE}/*`), () => HttpResponse.json({})));
}

describe('recipes', () => {
  it("shows a recipe's versions and the active version's ingredients with their stock item names", async () => {
    inventoryServer();

    renderApp(RECEITAS);

    expect(await screen.findByRole('heading', { name: 'Ficha técnica: Polvo à lagareiro' })).toBeInTheDocument();
    const versions = screen.getByRole('table', { name: 'Versões da ficha técnica' });
    expect(rowsOf(versions, 3)).toEqual([
      ['v1', '1 un', 'Substituída'],
      ['v2', '1 un', 'Ativa'],
      ['v3', '1 un', 'Rascunho'],
    ]);
    const ingredients = screen.getByRole('table', { name: 'Ingredientes da versão ativa' });
    await waitFor(() =>
      expect(rowsOf(ingredients, 2)).toEqual([
        ['Polvo fresco', '0,35 kg'],
        ['Azeite virgem extra', '0,06 L'],
      ]),
    );
  });

  it('creates a recipe by its name', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(RECEITAS);

    await user.click(await screen.findByRole('button', { name: 'Nova ficha técnica' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova ficha técnica' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Nome' }), 'Arroz de marisco');
    await user.click(within(dialog).getByRole('button', { name: 'Criar ficha técnica' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ path: `${BASE}/recipes`, body: { name: 'Arroz de marisco' } });
    expect(commands(state)[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('adds a draft version with its yield and ingredients', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(RECEITAS);

    await user.click(await screen.findByRole('button', { name: 'Nova versão' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova versão de Polvo à lagareiro' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Rendimento' }), '1');
    await user.type(within(dialog).getByRole('textbox', { name: 'Unidade do rendimento' }), 'un');
    await user.selectOptions(await within(dialog).findByRole('combobox', { name: 'Ingrediente 1' }), polvo.name);
    await user.type(within(dialog).getByRole('textbox', { name: 'Quantidade 1' }), '0,4');
    await user.type(within(dialog).getByRole('textbox', { name: 'Unidade 1' }), 'kg');
    await user.click(within(dialog).getByRole('button', { name: 'Criar versão' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({
      path: `${BASE}/recipes/${polvoLagareiro.id}/versions`,
      body: {
        yieldQuantity: '1',
        yieldUnit: 'un',
        ingredients: [{ stockItemId: polvo.id, quantity: '0.4', unit: 'kg', kind: 'INGREDIENT' }],
      },
    });
  });

  it('activates a draft version after confirming it', async () => {
    const state = inventoryServer();
    acceptCommands();
    const { user } = renderApp(RECEITAS);

    await user.click(await screen.findByRole('button', { name: 'Ativar v3' }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Ativar versão' }));

    await waitFor(() => expect(commands(state)).toHaveLength(1));
    expect(commands(state)[0]).toMatchObject({ path: `${BASE}/recipes/${polvoLagareiro.id}/versions/3/activate`, body: {} });
  });

  it('says so when there are no recipes yet', async () => {
    inventoryServer({ recipes: [] });

    renderApp(RECEITAS);

    expect(await screen.findByText('Ainda não há fichas técnicas.')).toBeInTheDocument();
  });

  it('hides the recipe commands from a user without RECIPE_MANAGE', async () => {
    inventoryServer({ session: admin });

    renderApp(RECEITAS);

    await screen.findByRole('heading', { name: 'Ficha técnica: Polvo à lagareiro' });
    for (const name of ['Nova ficha técnica', 'Nova versão', 'Ativar v3']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });
});
