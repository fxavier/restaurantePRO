import { act, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  admin,
  api,
  baixa,
  companyA,
  companyB,
  problem,
  sessionIn,
  signedIn,
  sommerschield,
  store,
  storePage,
  type TenantChoice,
} from './test/fixtures';
import { server } from './test/server';
import { CSRF_TOKEN, renderApp } from './test/render-app';

const LOGIN = '/oauth2/authorization/keycloak';

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
}

describe('BackOffice access shell', () => {
  it('sends a signed-out user to the BFF login', async () => {
    server.use(
      http.get(api('/api/v1/auth/session'), () => problem(401, 'BFF_SESSION_EXPIRED')),
      http.get(api('/api/v1/auth/tenants'), () => problem(401, 'BFF_SESSION_EXPIRED')),
    );

    const { leave } = renderApp();

    expect(await screen.findByText('A redirecionar para o início de sessão…')).toBeInTheDocument();
    expect(leave).toHaveBeenCalledWith(LOGIN);
    expect(screen.queryByRole('navigation', { name: 'Áreas' })).not.toBeInTheDocument();
  });

  it('shows a signed-in user the shell: areas, company, store and user menu', async () => {
    server.use(...signedIn());

    renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Visão geral' })).toBeInTheDocument();
    const areas = screen.getByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: 'Visão geral' })).toHaveAttribute('aria-current', 'page');
    expect(within(areas).getByRole('link', { name: 'Terminais' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Menu do utilizador: Ana Admin' })).toBeInTheDocument();
    expect(await screen.findByRole('combobox', { name: 'Loja' })).toBeInTheDocument();
  });

  it('asks the store list for its first page, in the parameters Spring reads', async () => {
    let query = '';
    server.use(...signedIn());
    server.use(http.get(api('/api/v1/backoffice/stores'), ({ request }) => {
        query = new URL(request.url).search;
        return HttpResponse.json(storePage([baixa]));
      }),
    );

    renderApp();

    await screen.findByRole('combobox', { name: 'Loja' });
    expect(query).toBe('?page=0&size=100');
  });

  it('shows no company switcher to a user with one membership, only the company name', async () => {
    server.use(...signedIn({ tenants: [companyA] }));

    renderApp();

    expect(await screen.findByText('Restaurante A, Lda')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Empresa/ })).not.toBeInTheDocument();
  });

  it('lets a user with two memberships switch company, then reloads everything for the new one', async () => {
    let selected: TenantChoice = companyA;
    const switches: { body: unknown; csrf: string | null }[] = [];
    let sessionCalls = 0;
    server.use(
      http.get(api('/api/v1/auth/session'), () => {
        sessionCalls += 1;
        return HttpResponse.json(sessionIn(selected));
      }),
      http.get(api('/api/v1/auth/tenants'), () => HttpResponse.json([companyA, companyB])),
      http.get(api('/api/v1/backoffice/stores'), () =>
        HttpResponse.json(storePage(selected === companyA ? [baixa, sommerschield] : [store('33', 'Loja Matola'), store('44', 'Loja Maxixe')])),
      ),
      http.put(api('/bff/tenant'), async ({ request }) => {
        const body = (await request.json()) as { tenantId: string };
        switches.push({ body, csrf: request.headers.get('X-XSRF-TOKEN') });
        selected = body.tenantId === companyB.tenantId ? companyB : companyA;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, currentUrl } = renderApp(`/?storeId=${baixa.id}`);

    const switcher = await screen.findByRole('button', { name: 'Empresa: Restaurante A, Lda' });
    expect(await screen.findByRole('option', { name: 'Loja Baixa', selected: true })).toBeInTheDocument();
    const sessionCallsBefore = sessionCalls;

    await user.click(switcher);
    await user.click(await screen.findByRole('menuitemradio', { name: 'Restaurante B, Lda' }));

    expect(await screen.findByRole('button', { name: 'Empresa: Restaurante B, Lda' })).toBeInTheDocument();
    expect(await screen.findByRole('option', { name: 'Loja Matola' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Loja Baixa' })).not.toBeInTheDocument();
    expect(switches).toEqual([{ body: { tenantId: companyB.tenantId }, csrf: CSRF_TOKEN }]);
    expect(sessionCalls).toBeGreaterThan(sessionCallsBefore);
    expect(currentUrl()).toBe('/');
    await user.click(screen.getByRole('button', { name: 'Empresa: Restaurante B, Lda' }));
    expect(await screen.findByRole('menuitemradio', { name: 'Restaurante B, Lda' })).toBeChecked();
    expect(localStorage.length).toBe(0);
  });

  it('labels the company from the session, even when another tab chose a different one', async () => {
    // This tab last chose A; another tab of the same BFF session switched to B: the server is the truth.
    server.use(...signedIn({ tenants: [companyA, companyB], session: sessionIn(companyB) }));

    const { user } = renderApp();

    const switcher = await screen.findByRole('button', { name: 'Empresa: Restaurante B, Lda' });
    await user.click(switcher);
    expect(await screen.findByRole('menuitemradio', { name: 'Restaurante B, Lda' })).toBeChecked();
    expect(screen.getByRole('menuitemradio', { name: 'Restaurante A, Lda' })).not.toBeChecked();
  });

  it.each([
    [
      'the window regains focus',
      () => {
        window.dispatchEvent(new Event('blur'));
        window.dispatchEvent(new Event('focus'));
      },
    ],
    [
      'the tab becomes visible',
      () => {
        setVisibility('hidden');
        document.dispatchEvent(new Event('visibilitychange'));
        setVisibility('visible');
        document.dispatchEvent(new Event('visibilitychange'));
      },
    ],
  ])('rereads the session when %s, so a switch made in another tab shows at once', async (_, wake) => {
    let resolvedIn: TenantChoice = companyA;
    server.use(...signedIn({ tenants: [companyA, companyB] }));
    server.use(http.get(api('/api/v1/auth/session'), () => HttpResponse.json(sessionIn(resolvedIn))));

    renderApp();
    await screen.findByRole('button', { name: 'Empresa: Restaurante A, Lda' });

    resolvedIn = companyB; // another tab switched the shared BFF selection
    act(() => wake());

    expect(await screen.findByRole('button', { name: 'Empresa: Restaurante B, Lda' })).toBeInTheDocument();
  });

  it('shows the single company with the name the session gives', async () => {
    server.use(
      ...signedIn({ tenants: [companyA], session: { ...admin, tenantName: 'Restaurante A (nome da sessão)' } }),
    );

    renderApp();

    expect(await screen.findByText('Restaurante A (nome da sessão)')).toBeInTheDocument();
  });

  it('writes nothing about the company to localStorage', async () => {
    server.use(...signedIn({ tenants: [companyA, companyB] }));

    renderApp();

    await screen.findByRole('button', { name: 'Empresa: Restaurante A, Lda' });
    expect(localStorage.length).toBe(0);
  });

  it('asks a user with two memberships and no selection to choose the company first', async () => {
    let chosen = false;
    server.use(...signedIn({ tenants: [companyA, companyB] }));
    server.use(http.get(api('/api/v1/auth/session'), () =>
        chosen ? HttpResponse.json(sessionIn(companyB)) : problem(400, 'TENANT_SELECTION_REQUIRED'),
      ),
      http.put(api('/bff/tenant'), () => {
        chosen = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const { user } = renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Escolha a empresa' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Restaurante B, Lda/ }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Visão geral' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Empresa: Restaurante B, Lda' })).toBeInTheDocument();
  });

  it('keeps the selected store in the address, so a reload shows the same store', async () => {
    server.use(...signedIn());

    const first = renderApp('/');
    await screen.findByRole('option', { name: 'Loja Sommerschield' });
    await first.user.selectOptions(screen.getByRole('combobox', { name: 'Loja' }), 'Loja Sommerschield');

    expect(first.currentUrl()).toBe(`/?storeId=${sommerschield.id}`);
    const areas = screen.getByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: 'Terminais' })).toHaveAttribute(
      'href',
      `/organization/terminals?storeId=${sommerschield.id}`,
    );

    const reloadUrl = first.currentUrl();
    first.unmount();
    renderApp(reloadUrl);

    expect(await screen.findByRole('option', { name: 'Loja Sommerschield', selected: true })).toBeInTheDocument();
  });

  it('selects the only store on its own', async () => {
    server.use(...signedIn({ stores: [baixa] }));

    const { currentUrl } = renderApp('/');

    await waitFor(() => expect(currentUrl()).toBe(`/?storeId=${baixa.id}`));
  });

  it('shows an area to a user with its permission in one store only', async () => {
    server.use(...signedIn({ session: { ...admin, permissions: [`PERM_TERMINAL_MANAGE@${baixa.id}`] } }));

    renderApp();

    const areas = await screen.findByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: 'Terminais' })).toBeInTheDocument();
  });

  it('hides an area the user has no permission for', async () => {
    server.use(...signedIn({ session: { ...admin, permissions: ['PERM_STORE_MANAGE', 'TERMINAL_MANAGE'] } }));

    renderApp();

    const areas = await screen.findByRole('navigation', { name: 'Áreas' });
    expect(within(areas).getByRole('link', { name: 'Visão geral' })).toBeInTheDocument();
    expect(within(areas).queryByRole('link', { name: 'Terminais' })).not.toBeInTheDocument();
    expect(within(areas).queryByText('Organização')).not.toBeInTheDocument();
  });

  it('goes to the login when the session expires while the user works', async () => {
    server.use(...signedIn());
    server.use(http.get(api('/api/v1/backoffice/stores'), () => problem(401, 'BFF_SESSION_EXPIRED')),
    );

    const { leave } = renderApp();

    await waitFor(() => expect(leave).toHaveBeenCalledWith(LOGIN));
  });

  it.each([
    ['TENANT_SUSPENDED', 'Empresa suspensa'],
    ['TENANT_CLOSED', 'Empresa encerrada'],
  ])('shows a full-page state for %s', async (code, heading) => {
    server.use(...signedIn());
    server.use(http.get(api('/api/v1/auth/session'), () => problem(403, code)));

    renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Áreas' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Terminar sessão' })).toBeInTheDocument();
  });

  it('shows "Sem ligação" when the service cannot be reached, and recovers on "Tentar novamente"', async () => {
    let down = true;
    server.use(...signedIn());
    server.use(http.get(api('/api/v1/auth/session'), () => (down ? HttpResponse.error() : HttpResponse.json(admin))),
    );

    const { user } = renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Sem ligação' })).toBeInTheDocument();
    expect(screen.getByText('Nenhuma operação é confirmada enquanto o serviço não responder.')).toBeInTheDocument();

    down = false;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Visão geral' })).toBeInTheDocument();
  });

  it('treats the BFF reporting the backend unavailable as "Sem ligação"', async () => {
    server.use(...signedIn());
    server.use(http.get(api('/api/v1/auth/session'), () => problem(502, 'BACKEND_UNAVAILABLE')));

    renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Sem ligação' })).toBeInTheDocument();
  });

  it('signs out from the user menu with the CSRF header, then goes to the login', async () => {
    let csrf: string | null = null;
    server.use(...signedIn());
    server.use(http.post(api('/bff/logout'), ({ request }) => {
        csrf = request.headers.get('X-XSRF-TOKEN');
        return new HttpResponse(null, { status: 302, headers: { Location: 'https://idp.example/logout' } });
      }),
    );
    const { user, leave } = renderApp();

    await user.click(await screen.findByRole('button', { name: 'Menu do utilizador: Ana Admin' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Terminar sessão' }));

    await waitFor(() => expect(leave).toHaveBeenCalledWith(LOGIN));
    expect(csrf).toBe(CSRF_TOKEN);
  });

  it('opens the terminals area for the selected store', async () => {
    server.use(...signedIn());

    const { user, currentUrl } = renderApp(`/?storeId=${baixa.id}`);

    await user.click(await screen.findByRole('link', { name: 'Terminais' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Terminais' })).toBeInTheDocument();
    expect(currentUrl()).toBe(`/organization/terminals?storeId=${baixa.id}`);
  });
  it.each([
    ['a 502', () => problem(502, 'BACKEND_UNAVAILABLE')],
    ['a network failure', () => HttpResponse.error()],
  ])('shows "Sem ligação" when the company must be chosen but the list of companies fails with %s', async (_, answer) => {
    let down = true;
    server.use(...signedIn({ tenants: [companyA, companyB] }));
    server.use(
      http.get(api('/api/v1/auth/session'), () => problem(400, 'TENANT_SELECTION_REQUIRED')),
      http.get(api('/api/v1/auth/tenants'), () => (down ? answer() : HttpResponse.json([companyA, companyB]))),
    );

    const { user } = renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Sem ligação' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sem acesso' })).not.toBeInTheDocument();

    down = false;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Escolha a empresa' })).toBeInTheDocument();
  });

  it.each([
    ['a 403', () => problem(403, 'FORBIDDEN')],
    ['a network failure', () => HttpResponse.error()],
  ])('says the logout failed, and stays, when the BFF answers %s', async (_, answer) => {
    server.use(...signedIn());
    server.use(http.post(api('/bff/logout'), () => answer()));
    const { user, leave } = renderApp();

    await user.click(await screen.findByRole('button', { name: 'Menu do utilizador: Ana Admin' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Terminar sessão' }));

    expect(await screen.findByText('Não foi possível terminar a sessão. Tente novamente.')).toBeInTheDocument();
    expect(leave).not.toHaveBeenCalled();
  });

  it('does not loop on the login: a second expiry right after a redirect asks before going again', async () => {
    sessionStorage.setItem('restaurantpro.backoffice.loginRedirectAt', String(Date.now() - 5_000));
    server.use(
      http.get(api('/api/v1/auth/session'), () => problem(401, 'BFF_SESSION_EXPIRED')),
      http.get(api('/api/v1/auth/tenants'), () => problem(401, 'BFF_SESSION_EXPIRED')),
    );

    const { user, leave } = renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Sessão terminada' })).toBeInTheDocument();
    expect(screen.getByText(/não foi possível manter a sessão/i)).toBeInTheDocument();
    expect(leave).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Iniciar sessão' }));

    expect(leave).toHaveBeenCalledWith(LOGIN);
  });

  it('redirects again when the previous login redirect is not recent', async () => {
    sessionStorage.setItem('restaurantpro.backoffice.loginRedirectAt', String(Date.now() - 5 * 60_000));
    server.use(
      http.get(api('/api/v1/auth/session'), () => problem(401, 'BFF_SESSION_EXPIRED')),
      http.get(api('/api/v1/auth/tenants'), () => problem(401, 'BFF_SESSION_EXPIRED')),
    );

    const { leave } = renderApp();

    await waitFor(() => expect(leave).toHaveBeenCalledWith(LOGIN));
  });

  it('forgets the login redirect once the session works', async () => {
    sessionStorage.setItem('restaurantpro.backoffice.loginRedirectAt', String(Date.now() - 5_000));
    server.use(...signedIn());

    renderApp();

    await screen.findByRole('heading', { level: 1, name: 'Visão geral' });
    expect(sessionStorage.getItem('restaurantpro.backoffice.loginRedirectAt')).toBeNull();
  });

  it('drops a ?storeId that is not one of the company stores', async () => {
    server.use(...signedIn());

    const { currentUrl } = renderApp('/organization/terminals?storeId=not-a-store-of-this-company');

    await screen.findByRole('option', { name: 'Loja Baixa' });
    await waitFor(() => expect(currentUrl()).toBe('/organization/terminals'));
    expect(screen.getByRole('option', { name: 'Escolher loja', selected: true })).toBeInTheDocument();
  });
});
