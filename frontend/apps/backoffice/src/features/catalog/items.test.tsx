import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { bebidas, bife, carnes, catalogReference, ivaNormal, page, sangria, unidade, type ItemSummary } from './test-fixtures';
import { api, problem, signedIn } from '../../test/fixtures';
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

  it('narrows the list by family and by a search on name or code', async () => {
    itemsServer([bife, sangria]);

    const { user } = renderApp('/catalog');

    const table = await screen.findByRole('table', { name: 'Artigos do catálogo' });
    const families = screen.getByRole('group', { name: 'Famílias' });
    expect(within(families).getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Todas as famílias2',
      'Carnes1',
      'Bebidas1',
    ]);
    expect(within(families).getByRole('button', { name: /Todas/ })).toHaveAttribute('aria-pressed', 'true');

    await user.click(within(families).getByRole('button', { name: /Bebidas/ }));
    expect(rowsOf(table).map(([name]) => name)).toEqual(['SangriaB07']);

    await user.click(within(families).getByRole('button', { name: /Todas/ }));
    await user.type(screen.getByRole('searchbox', { name: 'Pesquisar artigos' }), 'c09');
    expect(rowsOf(table).map(([name]) => name)).toEqual(['Bife da VaziaC09']);

    await user.clear(screen.getByRole('searchbox', { name: 'Pesquisar artigos' }));
    await user.type(screen.getByRole('searchbox', { name: 'Pesquisar artigos' }), 'nada');
    expect(screen.getByText('Nenhum artigo corresponde à pesquisa.')).toBeInTheDocument();
  });

  it('says so when the company has no item yet', async () => {
    itemsServer();

    renderApp('/catalog');

    expect(await screen.findByText('Ainda não há artigos.')).toBeInTheDocument();
  });

  it('shows "Sem ligação" with a retry when the list cannot be reached', async () => {
    let calls = 0;
    server.use(...signedIn(), ...catalogReference());
    server.use(
      http.get(api('/api/v1/backoffice/catalog/items'), () => {
        calls += 1;
        return calls <= 3 ? problem(502, 'BACKEND_UNAVAILABLE') : HttpResponse.json(page([bife]));
      }),
    );

    const { user } = renderApp('/catalog');

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByText('Bife da Vazia')).toBeInTheDocument();
  });

  it('creates an item with its family, unit, tax category and production destination', async () => {
    const state = itemsServer([bife]);
    const sent: unknown[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/catalog/items'), async ({ request }) => {
        const body = (await request.json()) as ItemSummary;
        sent.push(body);
        const created = { ...body, id: 'e0000000-0000-4000-8000-000000000009', availability: 'ACTIVE', version: 0 };
        state.items.push(created);
        return HttpResponse.json(created);
      }),
    );

    const { user } = renderApp('/catalog');

    await user.click(await screen.findByRole('button', { name: 'Novo artigo' }));
    const dialog = screen.getByRole('dialog', { name: 'Novo artigo' });
    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));
    expect(within(dialog).getByText('Escolha a família.')).toBeInTheDocument();
    expect(sent).toEqual([]);

    await user.type(within(dialog).getByLabelText('Código'), 'b08');
    await user.type(within(dialog).getByLabelText('Nome'), 'Cerveja 2M');
    await user.selectOptions(within(dialog).getByLabelText('Família'), 'Bebidas');
    await user.selectOptions(within(dialog).getByLabelText('Unidade'), 'Unidade');
    await user.selectOptions(within(dialog).getByLabelText('Categoria de IVA'), 'IVA normal (16%)');
    await user.selectOptions(within(dialog).getByLabelText('Destino de produção'), 'Bar');
    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));

    expect(await screen.findByText('Cerveja 2M')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(sent).toEqual([
      {
        code: 'B08',
        name: 'Cerveja 2M',
        familyId: bebidas.id,
        unitId: unidade.id,
        taxCategoryId: ivaNormal.id,
        productionDestination: 'BAR',
        soldByWeight: false,
      },
    ]);
  });

  it('edits an item, deactivating it under the version it was read at', async () => {
    const state = itemsServer([bife]);
    const sent: unknown[] = [];
    server.use(
      http.put(api('/api/v1/backoffice/catalog/items/:id'), async ({ request }) => {
        const body = (await request.json()) as ItemSummary;
        sent.push(body);
        state.items = [{ ...bife, ...body, version: 4 }];
        return HttpResponse.json(state.items[0]);
      }),
    );

    const { user } = renderApp('/catalog');

    await user.click(await screen.findByRole('button', { name: 'Editar Bife da Vazia' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar artigo' });
    expect(within(dialog).getByLabelText('Código')).toHaveValue('C09');
    await user.selectOptions(within(dialog).getByLabelText('Estado'), 'Inativo');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    const table = await screen.findByRole('table', { name: 'Artigos do catálogo' });
    expect(await within(table).findByText('Inativo')).toBeInTheDocument();
    expect(sent).toEqual([
      {
        code: 'C09',
        name: 'Bife da Vazia',
        familyId: carnes.id,
        unitId: unidade.id,
        taxCategoryId: ivaNormal.id,
        productionDestination: 'KITCHEN',
        soldByWeight: false,
        kind: 'STANDARD',
        availability: 'INACTIVE',
        version: 3,
      },
    ]);
  });

  it('shows the refusal to a user the API does not let read the catalog', async () => {
    server.use(...signedIn(), ...catalogReference());
    server.use(http.get(api('/api/v1/backoffice/catalog/items'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp('/catalog');

    expect(await screen.findByText('Sem permissão para ver o catálogo.')).toBeInTheDocument();
  });
});
