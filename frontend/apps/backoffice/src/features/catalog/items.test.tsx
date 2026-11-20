import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { bife, catalogReference, page, sangria, type ItemSummary } from '../../test/catalog-fixtures';
import { api, signedIn } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

/** The company's items, as the server keeps them; the list requests are recorded. */
function itemsServer(initial: ItemSummary[] = []) {
  const state = { items: [...initial], lists: [] as string[] };
  server.use(...signedIn(), ...catalogReference());
  server.use(
    http.get(api('/api/v1/backoffice/catalog/items'), ({ request }) => {
      state.lists.push(new URL(request.url).search);
      return HttpResponse.json(page(state.items));
    }),
  );
  return state;
}

function rowsOf(table: HTMLElement) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').slice(0, 5).map((cell) => cell.textContent));
}

describe('Artigos', () => {
  it('lists the items with their family, production destination, tax and state', async () => {
    const state = itemsServer([bife, sangria]);

    renderApp('/catalog');

    const table = await screen.findByRole('table', { name: 'Artigos do catálogo' });
    expect(rowsOf(table)).toEqual([
      ['Bife da VaziaC09', 'Carnes', 'Cozinha', 'IVA normal (16%)', 'Ativo'],
      ['SangriaB07', 'Bebidas', 'Bar', 'IVA normal (16%)', 'Ativo'],
    ]);
    expect(state.lists).toEqual(['?page=0&size=200']);
  });
});
