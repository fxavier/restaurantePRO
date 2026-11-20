import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { bife, catalogReference, page, type BarcodeSummary, type ItemVariantSummary } from '../../test/catalog-fixtures';
import { api, signedIn } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const malPassado: ItemVariantSummary = { id: 'd0000000-0000-4000-8000-000000000001', itemId: bife.id, code: 'MP', name: 'Mal passado', version: 0 };

/** One item with its variants and barcodes, as the server keeps them; creates are recorded. */
function detailsServer() {
  const state = { variants: [malPassado], barcodes: [] as BarcodeSummary[], sent: [] as { path: string; body: unknown }[] };
  server.use(...signedIn(), ...catalogReference());
  server.use(
    http.get(api('/api/v1/backoffice/catalog/items'), () => HttpResponse.json(page([bife]))),
    http.get(api('/api/v1/backoffice/catalog/items/:id/variants'), () => HttpResponse.json(state.variants)),
    http.post(api('/api/v1/backoffice/catalog/items/:id/variants'), async ({ request, params }) => {
      const body = (await request.json()) as ItemVariantSummary;
      state.sent.push({ path: `/items/${String(params.id)}/variants`, body });
      const created = { ...body, id: 'd0000000-0000-4000-8000-000000000002', itemId: bife.id, version: 0 };
      state.variants.push(created);
      return HttpResponse.json(created);
    }),
    http.get(api('/api/v1/backoffice/catalog/barcodes'), () => HttpResponse.json(state.barcodes)),
    http.post(api('/api/v1/backoffice/catalog/barcodes'), async ({ request }) => {
      const body = (await request.json()) as BarcodeSummary;
      state.sent.push({ path: '/barcodes', body });
      const created = { ...body, id: 'ba000000-0000-4000-8000-000000000001', version: 0 };
      state.barcodes.push(created);
      return HttpResponse.json(created);
    }),
  );
  return state;
}

describe("an item's sheet", () => {
  it('lists the variants and adds one', async () => {
    const state = detailsServer();

    const { user } = renderApp('/catalog');

    await user.click(await screen.findByRole('button', { name: 'Ficha de Bife da Vazia' }));
    const sheet = screen.getByRole('dialog', { name: 'Bife da Vazia' });
    const variants = await within(sheet).findByRole('list', { name: 'Variantes' });
    expect(within(variants).getAllByRole('listitem').map((row) => row.textContent)).toEqual(['Mal passadoMP']);

    await user.type(within(sheet).getByLabelText('Código da variante'), 'bp');
    await user.type(within(sheet).getByLabelText('Nome da variante'), 'Bem passado');
    await user.click(within(sheet).getByRole('button', { name: 'Adicionar variante' }));

    expect(await within(variants).findByText('Bem passado')).toBeInTheDocument();
    expect(state.sent).toEqual([{ path: `/items/${bife.id}/variants`, body: { code: 'BP', name: 'Bem passado' } }]);
    expect(within(sheet).getByLabelText('Nome da variante')).toHaveValue('');
  });

  it('adds a barcode for one of its variants', async () => {
    const state = detailsServer();

    const { user } = renderApp('/catalog');

    await user.click(await screen.findByRole('button', { name: 'Ficha de Bife da Vazia' }));
    const sheet = screen.getByRole('dialog', { name: 'Bife da Vazia' });
    expect(await within(sheet).findByText('Ainda não há códigos de barras.')).toBeInTheDocument();

    await user.type(within(sheet).getByLabelText('Código de barras'), '5601234567890');
    await user.selectOptions(within(sheet).getByLabelText('Variante do código'), 'Mal passado');
    await user.click(within(sheet).getByRole('button', { name: 'Adicionar código' }));

    const barcodes = await within(sheet).findByRole('list', { name: 'Códigos de barras' });
    expect(await within(barcodes).findByText('5601234567890')).toBeInTheDocument();
    expect(state.sent).toEqual([{ path: '/barcodes', body: { code: '5601234567890', itemId: bife.id, variantId: malPassado.id } }]);
  });
});
