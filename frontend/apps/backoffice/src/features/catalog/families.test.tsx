import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { bebidas, carnes, catalogReference, page, type FamilySummary } from './test-fixtures';
import { api, baixa, problem, signedIn } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

/** The company's families, as the server keeps them; creates and updates are recorded. */
function familiesServer(initial: FamilySummary[] = [carnes, bebidas]) {
  const state = { families: [...initial], sent: [] as { method: string; path: string; body: unknown }[] };
  server.use(...signedIn(), ...catalogReference());
  server.use(
    http.get(api('/api/v1/backoffice/catalog/items'), () => HttpResponse.json(page([]))),
    http.get(api('/api/v1/backoffice/catalog/families'), () => HttpResponse.json(page(state.families))),
  );
  return state;
}

describe('Famílias', () => {
  it('is a tab of the page, kept in the address with the store', async () => {
    familiesServer();

    const { user, currentUrl } = renderApp(`/catalog?storeId=${baixa.id}`);

    expect(await screen.findByRole('tab', { name: 'Artigos' })).toHaveAttribute('aria-selected', 'true');
    await user.click(screen.getByRole('tab', { name: 'Famílias' }));

    const table = await screen.findByRole('table', { name: 'Famílias do catálogo' });
    expect(
      within(table)
        .getAllByRole('row')
        .slice(1)
        .map((row) => within(row).getAllByRole('cell').slice(0, 3).map((cell) => cell.textContent)),
    ).toEqual([
      ['Carnes', 'CARNES', '—'],
      ['Bebidas', 'BEBIDAS', '—'],
    ]);
    expect(currentUrl()).toBe(`/catalog?storeId=${baixa.id}&tab=familias`);
  });

  it('creates a subfamily, validating the form in Portuguese and showing a taken code on its field', async () => {
    const state = familiesServer();
    let attempts = 0;
    server.use(
      http.post(api('/api/v1/backoffice/catalog/families'), async ({ request }) => {
        const body = (await request.json()) as FamilySummary;
        state.sent.push({ method: 'POST', path: '/families', body });
        attempts += 1;
        if (attempts === 1) return problem(409, 'CATALOG_CODE_TAKEN', 'Code taken');
        const created = { ...body, id: 'f0000000-0000-4000-8000-000000000009', version: 0 };
        state.families.push(created);
        return HttpResponse.json(created);
      }),
    );

    const { user } = renderApp('/catalog?tab=familias');

    await user.click(await screen.findByRole('button', { name: 'Nova família' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova família' });
    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));
    expect(within(dialog).getByText('Indique o código.')).toBeInTheDocument();
    expect(within(dialog).getByText('Indique o nome.')).toBeInTheDocument();
    expect(state.sent).toEqual([]);

    await user.type(within(dialog).getByLabelText('Código'), 'grelhados');
    await user.type(within(dialog).getByLabelText('Nome'), 'Grelhados');
    await user.selectOptions(within(dialog).getByLabelText('Família superior'), 'Carnes');
    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));
    expect(await within(dialog).findByText('Este código já está a ser utilizado no catálogo.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Criar' }));
    expect(await screen.findByRole('cell', { name: 'Grelhados' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(state.sent.map(({ body }) => body)).toEqual([
      { code: 'GRELHADOS', name: 'Grelhados', parentId: carnes.id },
      { code: 'GRELHADOS', name: 'Grelhados', parentId: carnes.id },
    ]);
  });

  it('renames a family, sending the version it was read at', async () => {
    const state = familiesServer();
    server.use(
      http.put(api('/api/v1/backoffice/catalog/families/:id'), async ({ request, params }) => {
        const body = (await request.json()) as FamilySummary;
        state.sent.push({ method: 'PUT', path: `/families/${String(params.id)}`, body });
        state.families = state.families.map((family) => (family.id === params.id ? { ...family, ...body, version: 1 } : family));
        return HttpResponse.json(state.families.find((family) => family.id === params.id));
      }),
    );

    const { user } = renderApp('/catalog?tab=familias');

    await user.click(await screen.findByRole('button', { name: 'Editar Bebidas' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar família' });
    await user.clear(within(dialog).getByLabelText('Nome'));
    await user.type(within(dialog).getByLabelText('Nome'), 'Bebidas & Bar');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByRole('cell', { name: 'Bebidas & Bar' })).toBeInTheDocument();
    expect(state.sent).toEqual([
      { method: 'PUT', path: `/families/${bebidas.id}`, body: { code: 'BEBIDAS', name: 'Bebidas & Bar', version: 0 } },
    ]);
  });
});
