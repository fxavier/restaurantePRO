import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { admin, baixa, signedIn } from '../test/fixtures';
import { renderApp } from '../test/render-app';
import { server } from '../test/server';

/** Every area of the sidebar, in order, as an administrator with every permission sees it (Stitch's sidebar). */
const AREAS = [
  ['Visão geral', '/'],
  ['Lojas', '/organization/stores'],
  ['Terminais', '/organization/terminals'],
  ['Salas & Mesas', '/organization/dining'],
  ['Catálogo', '/catalog'],
  ['Produção & Impressoras', '/production'],
  ['Stocks', '/inventory'],
  ['Clientes', '/customers'],
  ['Pedidos', '/orders'],
  ['Financeiro', '/finance'],
  ['Utilizadores & Empregados', '/people'],
  ['Zonas', '/delivery/zones'],
  ['Entregas', '/delivery/report'],
] as const;

describe('BackOffice areas', () => {
  it("shows an administrator every area, in Stitch's order, each carrying the store", async () => {
    server.use(...signedIn());

    renderApp(`/?storeId=${baixa.id}`);

    const areas = await screen.findByRole('navigation', { name: 'Áreas' });
    const links = within(areas).getAllByRole('link');
    expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual(
      AREAS.map(([name, path]) => [name, `${path}?storeId=${baixa.id}`]),
    );
    expect(within(areas).getByRole('list', { name: 'Organização' })).toBeInTheDocument();
    expect(within(areas).getByRole('list', { name: 'Delivery' })).toBeInTheDocument();
  });

  // The route and the sidebar only: each area's page owns its own heading and content (its feature tests check them).
  it.each([
    ['/organization/stores', 'Lojas'],
    ['/organization/dining', 'Salas & Mesas'],
    ['/catalog', 'Catálogo'],
    ['/production', 'Produção & Impressoras'],
    ['/inventory', 'Stocks'],
    ['/customers', 'Clientes'],
    ['/orders', 'Pedidos'],
    ['/finance', 'Financeiro'],
    ['/people', 'Utilizadores & Empregados'],
    ['/delivery/zones', 'Zonas'],
    ['/delivery/report', 'Entregas'],
  ])('%s is a route of its own, marked current in the sidebar', async (path, title) => {
    server.use(...signedIn());

    renderApp(path);

    const areas = await screen.findByRole('navigation', { name: 'Áreas' });
    await waitFor(() => expect(within(areas).getByRole('link', { name: title })).toHaveAttribute('aria-current', 'page'));
    expect(screen.queryByRole('heading', { name: 'Página não encontrada' })).not.toBeInTheDocument();
  });

  it('hides the Delivery group from a user with neither of its permissions', async () => {
    server.use(...signedIn({ session: { ...admin, permissions: ['PERM_CATALOG_MANAGE'] } }));

    renderApp();

    const areas = await screen.findByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: 'Catálogo' })).toBeInTheDocument();
    expect(within(areas).queryByRole('list', { name: 'Delivery' })).not.toBeInTheDocument();
    expect(within(areas).queryByRole('link', { name: 'Zonas' })).not.toBeInTheDocument();
  });
});
