import { screen, within } from '@testing-library/react';
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

  it.each([
    ['/organization/stores', 'Organização', 'Lojas'],
    ['/organization/dining', 'Organização', 'Salas & Mesas'],
    ['/catalog', 'Catálogo', 'Catálogo'],
    ['/production', 'Produção', 'Produção & Impressoras'],
    ['/inventory', 'Stocks', 'Stocks'],
    ['/customers', 'Clientes', 'Clientes'],
    ['/finance', 'Financeiro', 'Financeiro'],
    ['/people', 'Utilizadores', 'Utilizadores & Empregados'],
    ['/delivery/zones', 'Delivery', 'Zonas'],
    ['/delivery/report', 'Delivery', 'Entregas'],
  ])('%s is its own page, marked current in the sidebar', async (path, eyebrow, title) => {
    server.use(...signedIn());

    renderApp(path);

    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(within(main).getByText(eyebrow, { selector: 'p' })).toBeInTheDocument();
    expect(within(main).getByText('Em preparação.')).toBeInTheDocument();
    const areas = screen.getByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: title })).toHaveAttribute('aria-current', 'page');
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
