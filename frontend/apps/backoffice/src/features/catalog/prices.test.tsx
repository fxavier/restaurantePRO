import { formatMoney } from '@restaurantpro/ui';
import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  bife,
  catalogReference,
  page,
  salaList,
  sangria,
  type ItemPriceSummary,
  type PriceListSummary,
} from './test-fixtures';
import { api, baixa, problem, signedIn } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const PRICES = `/catalog?storeId=${baixa.id}&tab=precos`;
const bifePrice: ItemPriceSummary = {
  id: 'b1000000-0000-4000-8000-000000000001',
  priceListId: salaList.id,
  itemId: bife.id,
  price: { amount: '450.00', currency: 'MZN' },
  version: 2,
};

/** A store's price lists and their prices, as the server keeps them; writes are recorded. */
function pricesServer({ lists = [salaList], prices = [bifePrice] }: { lists?: PriceListSummary[]; prices?: ItemPriceSummary[] } = {}) {
  const state = { lists: [...lists], prices: [...prices], listReads: [] as string[], sent: [] as { method: string; path: string; body: unknown }[] };
  server.use(...signedIn(), ...catalogReference());
  server.use(
    http.get(api('/api/v1/backoffice/catalog/items'), () => HttpResponse.json(page([bife, sangria]))),
    http.get(api('/api/v1/backoffice/catalog/items/:id/variants'), () => HttpResponse.json([])),
    http.get(api('/api/v1/backoffice/catalog/price-lists'), ({ request }) => {
      state.listReads.push(new URL(request.url).search);
      return HttpResponse.json(state.lists);
    }),
    http.get(api('/api/v1/backoffice/catalog/price-lists/:id/prices'), () => HttpResponse.json(state.prices)),
    http.post(api('/api/v1/backoffice/catalog/price-lists'), async ({ request }) => {
      const body = (await request.json()) as PriceListSummary;
      state.sent.push({ method: 'POST', path: '/price-lists', body });
      const created = { ...body, id: 'b0000000-0000-4000-8000-000000000002', currency: 'MZN', version: 0 };
      state.lists.push(created);
      return HttpResponse.json(created);
    }),
    http.post(api('/api/v1/backoffice/catalog/price-lists/:id/prices'), async ({ request, params }) => {
      const body = (await request.json()) as { itemId: string; amount: string };
      state.sent.push({ method: 'POST', path: `/price-lists/${String(params.id)}/prices`, body });
      const created = { id: 'b1000000-0000-4000-8000-000000000002', priceListId: salaList.id, itemId: body.itemId, price: { amount: body.amount, currency: 'MZN' }, version: 0 };
      state.prices.push(created);
      return HttpResponse.json(created);
    }),
    http.put(api('/api/v1/backoffice/catalog/price-lists/:id/prices/:priceId'), async ({ request, params }) => {
      const body = (await request.json()) as { amount: string; version: number };
      state.sent.push({ method: 'PUT', path: `/price-lists/${String(params.id)}/prices/${String(params.priceId)}`, body });
      state.prices = state.prices.map((price) =>
        price.id === params.priceId ? { ...price, price: { amount: body.amount, currency: 'MZN' }, version: body.version + 1 } : price,
      );
      return HttpResponse.json(state.prices.find((price) => price.id === params.priceId));
    }),
  );
  return state;
}

function priceRows() {
  const table = screen.getByRole('table', { name: 'Preços da tabela Sala' });
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').slice(0, 3).map((cell) => cell.textContent));
}

describe('Preços', () => {
  it('asks for a store first: price lists belong to one', async () => {
    const state = pricesServer();

    renderApp('/catalog?tab=precos');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver a tabela de preços.')).toBeInTheDocument();
    expect(state.listReads).toEqual([]);
  });

  it("shows the store's one price list and its prices, in the list's currency, with no way to add a second", async () => {
    const state = pricesServer();

    renderApp(PRICES);

    await screen.findByRole('table', { name: 'Preços da tabela Sala' });
    expect(screen.getByText('Tabela Sala')).toBeInTheDocument();
    expect(priceRows()).toEqual([['Bife da VaziaC09', '—', formatMoney({ amount: '450.00', currency: 'MZN' })]]);
    expect(screen.queryByRole('button', { name: 'Nova tabela' })).not.toBeInTheDocument();
    expect(state.listReads).toEqual([`?storeId=${baixa.id}`]);
  });

  it('creates the price list of a store that has none, and then offers no second one', async () => {
    const state = pricesServer({ lists: [], prices: [] });

    const { user } = renderApp(PRICES);

    expect(await screen.findByText('Esta loja ainda não tem tabela de preços.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Nova tabela' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova tabela de preços' });
    await user.type(within(dialog).getByLabelText('Nome'), 'Sala');
    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));

    expect(await screen.findByText('Tabela Sala')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Nova tabela' })).not.toBeInTheDocument();
    expect(state.sent).toEqual([{ method: 'POST', path: '/price-lists', body: { name: 'Sala', storeId: baixa.id } }]);
  });

  it('shows the list that won when another user gave the store its price list first', async () => {
    const state = pricesServer({ lists: [], prices: [] });
    server.use(
      http.post(api('/api/v1/backoffice/catalog/price-lists'), () => {
        state.lists.push(salaList);
        return problem(409, 'CATALOG_CODE_TAKEN');
      }),
    );

    const { user } = renderApp(PRICES);

    await user.click(await screen.findByRole('button', { name: 'Nova tabela' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova tabela de preços' });
    await user.type(within(dialog).getByLabelText('Nome'), 'Esplanada');
    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));

    expect(await screen.findByText('Tabela Sala')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('Este código já está a ser utilizado no catálogo.')).not.toBeInTheDocument();
  });

  it('prices an item, sending the amount as the decimal string typed, comma or point', async () => {
    const state = pricesServer();

    const { user } = renderApp(PRICES);

    await user.click(await screen.findByRole('button', { name: 'Definir preço' }));
    const dialog = screen.getByRole('dialog', { name: 'Definir preço' });
    await user.selectOptions(within(dialog).getByLabelText('Artigo'), 'Sangria (B07)');
    await user.type(within(dialog).getByLabelText('Preço'), '12a');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
    expect(within(dialog).getByText('Indique um valor como 1250,50.')).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText('Preço'));
    await user.type(within(dialog).getByLabelText('Preço'), '1250,50');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() =>
      expect(priceRows()).toEqual([
        ['Bife da VaziaC09', '—', formatMoney({ amount: '450.00', currency: 'MZN' })],
        ['SangriaB07', '—', formatMoney({ amount: '1250.50', currency: 'MZN' })],
      ]),
    );
    expect(state.sent).toEqual([
      { method: 'POST', path: `/price-lists/${salaList.id}/prices`, body: { itemId: sangria.id, amount: '1250.50' } },
    ]);
  });

  it('says, when pricing, that the items to choose from are cut at one page', async () => {
    pricesServer();
    server.use(http.get(api('/api/v1/backoffice/catalog/items'), () => HttpResponse.json(page([bife, sangria], 200, 250))));

    const { user } = renderApp(PRICES);

    await user.click(await screen.findByRole('button', { name: 'Definir preço' }));
    const dialog = screen.getByRole('dialog', { name: 'Definir preço' });
    expect(within(dialog).getByRole('note')).toHaveTextContent('Lista incompleta: mostra 2 de 250 artigos');
  });

  it('changes a price under the version it was read at, and shows the amount the server answered', async () => {
    const state = pricesServer();

    const { user } = renderApp(PRICES);

    await user.click(await screen.findByRole('button', { name: 'Alterar preço de Bife da Vazia' }));
    const dialog = screen.getByRole('dialog', { name: 'Alterar preço' });
    expect(within(dialog).getByLabelText('Preço')).toHaveValue('450.00');
    await user.clear(within(dialog).getByLabelText('Preço'));
    await user.type(within(dialog).getByLabelText('Preço'), '475');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(priceRows()).toEqual([['Bife da VaziaC09', '—', formatMoney({ amount: '475', currency: 'MZN' })]]));
    expect(state.sent).toEqual([
      { method: 'PUT', path: `/price-lists/${salaList.id}/prices/${bifePrice.id}`, body: { amount: '475', version: 2 } },
    ]);
  });
});
