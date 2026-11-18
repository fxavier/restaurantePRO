import { act, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  admin,
  api,
  baixa,
  companyA,
  companyB,
  companyC,
  problem,
  sessionIn,
  signedIn,
  sommerschield,
  store,
  storePage,
  type TenantChoice,
} from './test/fixtures';
import { resetLoginGuard } from './lib/browser';
import { server } from './test/server';
import { CSRF_TOKEN, renderApp } from './test/render-app';

/** A page load: the guards held in module memory start again; sessionStorage is kept, as the browser keeps it. */
function reloadPage() {
  resetLoginGuard();
  return renderApp();
}

const changedNotice = (tenantName: string) =>
  `A sessão ou a empresa ativa mudou noutro separador. Empresa ativa: ${tenantName}.`;

/** An answer as the BFF relays it with a company selected: it names that company (FE-F16). */
function relayedIn(company: TenantChoice, body: unknown, status = 200) {
  return HttpResponse.json(body as object, { status, headers: { 'X-Relayed-Tenant': company.tenantId ?? '' } });
}

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

  it('lets a user with two memberships switch company, then reloads the page in it', async () => {
    const switches: { body: unknown; csrf: string | null }[] = [];
    let selected: TenantChoice = companyA;
    server.use(...signedIn({ tenants: [companyA, companyB] }));
    server.use(
      http.get(api('/api/v1/auth/session'), () => relayedIn(selected, sessionIn(selected))),
      http.put(api('/bff/tenant'), async ({ request }) => {
        switches.push({ body: await request.json(), csrf: request.headers.get('X-XSRF-TOKEN') });
        selected = companyB;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, leave, queryClient } = renderApp(`/organization/terminals?storeId=${baixa.id}`);

    await user.click(await screen.findByRole('button', { name: 'Empresa: Restaurante A, Lda' }));
    await user.click(await screen.findByRole('menuitemradio', { name: 'Restaurante B, Lda' }));

    // A page load, as for a switch in another tab (FE-F16): nothing loaded for A survives, the store neither.
    await waitFor(() => expect(leave).toHaveBeenCalledWith('/organization/terminals'));
    // A reread before the page unloads finds B: it is this tab's own switch, not another tab's.
    await act(() => queryClient.invalidateQueries());
    expect(leave).not.toHaveBeenCalledWith('/');
    expect(switches).toEqual([{ body: { tenantId: companyB.tenantId }, csrf: CSRF_TOKEN }]);
    expect(sessionStorage.length).toBe(0); // no "changed in another tab" notice for the tab's own switch
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
  ])('reloads the page when %s and the session comes back in another company', async (_, wake) => {
    let resolvedIn: TenantChoice = companyA;
    server.use(...signedIn({ tenants: [companyA, companyB] }));
    server.use(http.get(api('/api/v1/auth/session'), () => relayedIn(resolvedIn, sessionIn(resolvedIn))));

    const { leave, unmount } = renderApp();
    await screen.findByRole('button', { name: 'Empresa: Restaurante A, Lda' });

    resolvedIn = companyB; // another tab switched the shared BFF selection
    act(() => wake());

    await waitFor(() => expect(leave).toHaveBeenCalledWith('/'));
    expect(screen.queryByRole('button', { name: 'Empresa: Restaurante B, Lda' })).not.toBeInTheDocument();

    unmount();
    reloadPage();
    expect(await screen.findByText(changedNotice('Restaurante B, Lda'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Empresa: Restaurante B, Lda' })).toBeInTheDocument();
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

    const { user, leave } = renderApp();

    expect(await screen.findByRole('heading', { level: 1, name: 'Escolha a empresa' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Restaurante B, Lda/ }));

    await waitFor(() => expect(leave).toHaveBeenCalledWith('/'));
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

  it('hides an area the user has no permission for', async () => {
    server.use(...signedIn({ session: { ...admin, permissions: ['STORE_MANAGE'] } }));

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

describe('Company guard (FE-F16): a tab only ever shows and acts in the session and company it was loaded in', () => {
  const AREAS = '/api/v1/backoffice/areas';
  const newArea = { code: 'S1', name: 'Sala', storeId: '11111111-0000-4000-8000-000000000001' };
  const matola = store('33333333-0000-4000-8000-000000000003', 'Loja Matola');
  const xai = store('55555555-0000-4000-8000-000000000005', 'Loja Xai-Xai');
  const ana = { userId: admin.userId, name: 'Ana Admin', memberOf: [companyA, companyB] };
  const bruno = { userId: '0b6c1b0e-0000-4000-8000-000000000002', name: 'Bruno', memberOf: [companyC] };
  const storesOf = (company: TenantChoice) =>
    company === companyA ? [baixa, sommerschield] : company === companyB ? [matola] : [xai];

  /** A write as a feature's mutation runs it: through the app's API client and query client, never retried. */
  function write(app: ReturnType<typeof renderApp>) {
    return app.queryClient
      .getMutationCache()
      .build(app.queryClient, { mutationFn: () => app.api.POST(AREAS, { body: newArea }) })
      .execute(undefined)
      .then(
        () => undefined,
        (error: unknown) => error,
      );
  }

  /**
   * The BFF as bff/README describes it: one session (the user who last logged in) shared by every tab, with or without a
   * selected company. With a selection, a write must expect it and every answer names it. Without one, the expected
   * tenant is relayed as the tenant and the backend refuses it to a non-member (D177); without either, the backend
   * resolves the user's only membership and the answer names nothing. `acted` records what the backend did, and where.
   */
  function fakeBff({ refuseWrites = false } = {}) {
    const bff = {
      user: ana,
      selected: companyA as TenantChoice | undefined,
      acted: [] as { method: string; path: string; in: string | undefined }[],
    };
    const relay = (request: Request, answer: (company: TenantChoice) => unknown, status = 200) => {
      const expected = request.headers.get('X-Expected-Tenant');
      const unsafe = request.method !== 'GET';
      let company: TenantChoice | undefined;
      let stamp = true;
      if (bff.selected) {
        if (unsafe && (refuseWrites || expected !== bff.selected.tenantId)) return problem(409, 'TENANT_CHANGED');
        company = bff.selected;
      } else if (expected) {
        company = bff.user.memberOf.find((c) => c.tenantId === expected);
        if (!company) {
          const refusal = problem(403, 'TENANT_FORBIDDEN');
          refusal.headers.set('X-Relayed-Tenant', expected);
          return refusal;
        }
      } else {
        if (bff.user.memberOf.length !== 1) return problem(400, 'TENANT_SELECTION_REQUIRED');
        company = bff.user.memberOf[0];
        stamp = false;
      }
      bff.acted.push({ method: request.method, path: new URL(request.url).pathname, in: company?.legalName });
      const headers: Record<string, string> = stamp && company?.tenantId ? { 'X-Relayed-Tenant': company.tenantId } : {};
      return HttpResponse.json(answer(company!) as object, { status, headers });
    };
    server.use(
      http.get(api('/api/v1/auth/session'), ({ request }) =>
        relay(request, (company) => sessionIn(company, { userId: bff.user.userId, name: bff.user.name })),
      ),
      http.get(api('/api/v1/auth/tenants'), ({ request }) => relay(request, () => bff.user.memberOf)),
      http.get(api('/api/v1/backoffice/stores'), ({ request }) => relay(request, (company) => storePage(storesOf(company)))),
      http.post(api(AREAS), ({ request }) => relay(request, () => ({}), 201)),
    );
    return bff;
  }

  const writesIn = (bff: ReturnType<typeof fakeBff>) => bff.acted.filter((a) => a.method === 'POST').map((a) => a.in);

  it('names the company the page was loaded in on every call, reads included', async () => {
    const seen: (string | null)[] = [];
    const bff = fakeBff();
    server.events.on('request:start', ({ request }) => {
      seen.push(request.headers.get('X-Expected-Tenant'));
    });
    const app = renderApp();
    await screen.findByRole('option', { name: 'Loja Baixa' });

    await act(() => write(app));
    server.events.removeAllListeners();

    expect(writesIn(bff)).toEqual(['Restaurante A, Lda']);
    expect(seen[0]).toBeNull(); // the first session read: nothing is pinned yet
    expect(seen.at(-1)).toBe(companyA.tenantId);
  });

  it('never shows an answer from another company: it reloads the page instead', async () => {
    const bff = fakeBff();
    const app = renderApp(`/?storeId=${baixa.id}`);
    await screen.findByRole('option', { name: 'Loja Baixa', selected: true });

    bff.selected = companyB;
    await act(() => app.queryClient.invalidateQueries());

    await waitFor(() => expect(app.leave).toHaveBeenCalledWith('/'));
    expect(screen.queryByRole('option', { name: 'Loja Matola' })).not.toBeInTheDocument();
  });

  it.each([
    ['another company', sessionIn(companyC, { userId: admin.userId })],
    ['another user', sessionIn(companyA, { userId: bruno.userId, name: 'Bruno' })],
  ])('reloads, showing nothing, when a session answer naming no tenant comes back for %s', async (_, other) => {
    fakeBff();
    const app = renderApp();
    await screen.findByRole('button', { name: 'Menu do utilizador: Ana Admin' });
    server.use(http.get(api('/api/v1/auth/session'), () => HttpResponse.json(other)));

    await act(() => app.queryClient.invalidateQueries({ queryKey: ['auth', 'session'] }));

    await waitFor(() => expect(app.leave).toHaveBeenCalledWith('/'));
    expect(screen.queryByText('Restaurante C, Lda')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Menu do utilizador: Bruno' })).not.toBeInTheDocument();
  });

  it('after another user logs in from another tab, neither a read nor a write of this tab acts in their company', async () => {
    const bff = fakeBff();
    const app = renderApp(`/?storeId=${baixa.id}`);
    await screen.findByRole('option', { name: 'Loja Baixa', selected: true });

    bff.user = bruno; // a new BFF session: Bruno, one membership, no selection
    bff.selected = undefined;
    await act(() => app.queryClient.invalidateQueries());
    const refused = await act(() => write(app));

    expect(refused).toMatchObject({ code: 'TENANT_CHANGED' });
    expect(bff.acted.filter((a) => a.in === 'Restaurante C, Lda')).toEqual([]);
    await waitFor(() => expect(app.leave).toHaveBeenCalledWith('/'));
    expect(screen.queryByRole('option', { name: 'Loja Xai-Xai' })).not.toBeInTheDocument();
  });

  it('reloads the page on 409 TENANT_CHANGED, and the reloaded page says which company is now active', async () => {
    const bff = fakeBff({ refuseWrites: true });
    const app = renderApp();
    await screen.findByRole('option', { name: 'Loja Baixa' });

    const failure = await act(() => write(app));

    expect(failure).toMatchObject({ status: 409, code: 'TENANT_CHANGED' });
    await waitFor(() => expect(app.leave).toHaveBeenCalledWith('/'));
    app.unmount();
    bff.selected = companyB;
    reloadPage();
    expect(
      await screen.findByText(`${changedNotice('Restaurante B, Lda')} A última alteração não foi guardada.`),
    ).toBeInTheDocument();
  });

  it('shows the notice once: the next page load does not repeat it', async () => {
    fakeBff();
    sessionStorage.setItem('restaurantpro.backoffice.tenantChanged', 'false');
    const first = renderApp();
    await screen.findByText(changedNotice('Restaurante A, Lda'));
    first.unmount();

    reloadPage();

    await screen.findByRole('option', { name: 'Loja Baixa' });
    expect(screen.queryByText(/mudou noutro separador/)).not.toBeInTheDocument();
  });

  it('A→B→A in other tabs: the page reloads, and no write lands in B while A is shown', async () => {
    const bff = fakeBff();
    const app = renderApp(`/?storeId=${baixa.id}`);
    await screen.findByRole('option', { name: 'Loja Baixa', selected: true });

    bff.selected = companyB; // another tab switches to B ...
    await act(() => app.queryClient.invalidateQueries());
    const duringB = await act(() => write(app));
    bff.selected = companyA; // ... and back to A
    await act(() => app.queryClient.invalidateQueries());
    await act(() => write(app));

    expect(app.leave).toHaveBeenCalledWith('/');
    expect(duringB).toMatchObject({ code: 'TENANT_CHANGED' });
    expect(writesIn(bff)).toEqual(['Restaurante A, Lda']);
    expect(screen.queryByRole('option', { name: 'Loja Matola' })).not.toBeInTheDocument();
  });
});
