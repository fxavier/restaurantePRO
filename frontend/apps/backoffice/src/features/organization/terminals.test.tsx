import { cleanup, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { resetLoginGuard } from '../../lib/browser';
import {
  admin,
  api,
  baixa,
  problem,
  signedIn,
  stepUpRequired,
  terminal,
  terminalPage,
  type TerminalSummary,
} from '../../test/fixtures';
import { CSRF_TOKEN, renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const TERMINALS = `/organization/terminals?storeId=${baixa.id}`;
const caixa1 = terminal('aaaa0000-0000-4000-8000-000000000001', 'CX1', 'Caixa 1', 'ACTIVE');
const bar = terminal('aaaa0000-0000-4000-8000-000000000002', 'BAR', 'Bar', 'REGISTERED');
const bridge = terminal('aaaa0000-0000-4000-8000-000000000003', 'PB1', 'Impressoras', 'REVOKED', baixa.id, 'PRINT_BRIDGE');

/** The one-time enrolment code (Crockford base32, 8 + 16) and how the dialog groups it. */
const CODE = 'ABCD1234EFGH5678JKMN9PQR';
const GROUPED = 'ABCD 1234 EFGH 5678 JKMN 9PQR';
const EXPIRES = '2026-11-19T08:00:00Z';
const A_KEY = expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

type Seen = { url: string; body: unknown; idempotencyKey: string | null; csrf: string | null };

async function seen(request: Request): Promise<Seen> {
  const text = await request.clone().text();
  return {
    url: new URL(request.url).pathname + new URL(request.url).search,
    body: text ? JSON.parse(text) : undefined,
    idempotencyKey: request.headers.get('Idempotency-Key'),
    csrf: request.headers.get('X-XSRF-TOKEN'),
  };
}

/** A store's terminals that the create and revoke handlers change, as the server would. */
function terminalsServer(initial: TerminalSummary[] = []) {
  const state = { terminals: [...initial], lists: [] as string[] };
  server.use(...signedIn());
  server.use(
    http.get(api('/api/v1/backoffice/terminals'), ({ request }) => {
      state.lists.push(new URL(request.url).search);
      return HttpResponse.json(terminalPage(state.terminals));
    }),
  );
  return state;
}

/** Everything the browser keeps that a script or a later visitor could read. */
function storedText() {
  const all: string[] = [];
  for (const storage of [localStorage, sessionStorage]) {
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i)!;
      all.push(key, storage.getItem(key) ?? '');
    }
  }
  return all.join('\n');
}

function newPageLoad() {
  cleanup();
  resetLoginGuard();
}

describe('terminals list', () => {
  it('asks for a store before listing anything', async () => {
    const state = terminalsServer();

    renderApp('/organization/terminals');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver os seus terminais.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Registar terminal' })).not.toBeInTheDocument();
    expect(state.lists).toEqual([]);
  });

  it('says so when the store has no terminal yet', async () => {
    terminalsServer();

    renderApp(TERMINALS);

    expect(await screen.findByText('Esta loja ainda não tem terminais.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Registar terminal' })).toBeEnabled();
  });

  it("lists the selected store's terminals with their kind and state, asking for a page in Spring's parameters", async () => {
    const state = terminalsServer([caixa1, bar, bridge]);

    renderApp(TERMINALS);

    const table = await screen.findByRole('table', { name: 'Terminais da loja' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getAllByRole('cell').slice(0, 4).map((cell) => cell.textContent))).toEqual([
      ['Caixa 1', 'CX1', 'Terminal POS', 'Ativo'],
      ['Bar', 'BAR', 'Terminal POS', 'Por ativar'],
      ['Impressoras', 'PB1', 'Ponte de impressão', 'Revogado'],
    ]);
    expect(within(rows[2]!).queryByRole('button', { name: /Emitir código/ })).not.toBeInTheDocument();
    expect(within(rows[2]!).queryByRole('button', { name: /Revogar/ })).not.toBeInTheDocument();
    expect(state.lists).toEqual([`?storeId=${baixa.id}&page=0&size=50`]);
  });

  it('shows the API refusal to a user without the permission who reaches the page', async () => {
    server.use(...signedIn({ session: { ...admin, permissions: ['PERM_STORE_MANAGE'] } }));
    server.use(
      http.get(api('/api/v1/backoffice/terminals'), () => problem(403, 'HTTP_403', 'Forbidden')),
    );

    renderApp(TERMINALS);

    expect(await screen.findByText('Sem permissão para ver os terminais desta loja.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Registar terminal' })).not.toBeInTheDocument();
  });

  it('shows "Sem ligação" with a retry when the list cannot be reached', async () => {
    let calls = 0;
    server.use(...signedIn());
    server.use(
      http.get(api('/api/v1/backoffice/terminals'), () => {
        calls += 1;
        return calls <= 3 ? problem(502, 'BACKEND_UNAVAILABLE') : HttpResponse.json(terminalPage([caixa1]));
      }),
    );

    const { user } = renderApp(TERMINALS);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('cell', { name: 'Caixa 1' })).toBeInTheDocument();
  });

  it('asks for a fresh second factor when the list itself needs it, and comes back to this page', async () => {
    server.use(...signedIn());
    server.use(http.get(api('/api/v1/backoffice/terminals'), () => stepUpRequired(300)));

    const { user, leave } = renderApp(TERMINALS);

    expect(await screen.findByText('Para ver os terminais, confirme a sua identidade.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirmar identidade' }));
    const target = new URL(leave.mock.calls[0]![0], 'http://localhost');
    expect(target.pathname).toBe('/bff/step-up');
    expect(Object.fromEntries(target.searchParams)).toEqual({ acr_values: '2', max_age: '300', return: TERMINALS });
    expect(storedText()).toBe('');
  });
});

describe('registering a terminal', () => {
  function createHandler(state: ReturnType<typeof terminalsServer>, answer?: (n: number) => Response | undefined) {
    const requests: Seen[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/terminals'), async ({ request }) => {
        requests.push(await seen(request));
        const custom = answer?.(requests.length);
        if (custom) return custom;
        const body = requests.at(-1)!.body as { code: string; name: string };
        const created = terminal(`bbbb0000-0000-4000-8000-00000000000${state.terminals.length}`, body.code, body.name);
        state.terminals.push(created);
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    return requests;
  }

  async function fillAndSubmit(user: ReturnType<typeof renderApp>['user'], code = 'CX2', name = 'Caixa 2') {
    await user.click(await screen.findByRole('button', { name: 'Registar terminal' }));
    const dialog = await screen.findByRole('dialog', { name: 'Registar terminal' });
    if (code) await user.type(within(dialog).getByLabelText('Código'), code);
    if (name) await user.type(within(dialog).getByLabelText('Nome'), name);
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));
    return dialog;
  }

  it('registers a POS terminal in the selected store and shows it in the list', async () => {
    const state = terminalsServer();
    const requests = createHandler(state);

    const { user } = renderApp(TERMINALS);
    await fillAndSubmit(user);

    expect(await screen.findByRole('cell', { name: 'Caixa 2' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Registar terminal' })).not.toBeInTheDocument();
    expect(requests).toEqual([
      {
        url: '/api/v1/backoffice/terminals',
        body: { storeId: baixa.id, code: 'CX2', name: 'Caixa 2', kind: 'POS' },
        // The contract requires an Idempotency-Key here: one per intent (FC §8).
        idempotencyKey: A_KEY,
        csrf: CSRF_TOKEN,
      },
    ]);
  });

  it('gives each registration its own key, and a corrected one after a refusal a new one', async () => {
    const state = terminalsServer([caixa1]);
    const requests = createHandler(state, (n) =>
      n === 1 ? problem(409, undefined, 'Conflict', { detail: "Terminal code 'CX1' is already used." }) : undefined,
    );

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user, 'CX1');
    expect(await within(dialog).findByText('Já existe um terminal com este código nesta loja.')).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText('Código'));
    await user.type(within(dialog).getByLabelText('Código'), 'CX2');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));
    expect(await screen.findByRole('cell', { name: 'Caixa 2' })).toBeInTheDocument();

    await fillAndSubmit(user, 'CX3', 'Caixa 3');
    expect(await screen.findByRole('cell', { name: 'Caixa 3' })).toBeInTheDocument();

    const keys = requests.map((request) => request.idempotencyKey);
    expect(keys).toEqual([A_KEY, A_KEY, A_KEY]);
    expect(new Set(keys).size).toBe(3);
  });

  it('sends the code as it is shown, in capitals and trimmed', async () => {
    const state = terminalsServer();
    const requests = createHandler(state);

    const { user } = renderApp(TERMINALS);
    await fillAndSubmit(user, ' cx2 ');

    expect(await screen.findByRole('cell', { name: 'CX2' })).toBeInTheDocument();
    expect(requests[0]?.body).toMatchObject({ code: 'CX2' });
  });

  it("names the tab's company on the write, and on 409 TENANT_CHANGED reloads instead of explaining inline", async () => {
    const state = terminalsServer();
    const expected: (string | null)[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/terminals'), ({ request }) => {
        expected.push(request.headers.get('X-Expected-Tenant'));
        return problem(409, 'TENANT_CHANGED');
      }),
    );

    const { user, leave } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user);

    await waitFor(() => expect(leave).toHaveBeenCalledWith('/'));
    expect(expected).toEqual([admin.tenantId]);
    expect(state.terminals).toEqual([]);
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'false');
  });

  it('checks the fields before sending', async () => {
    const state = terminalsServer();
    const requests = createHandler(state);

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user, 'CX2', '');

    expect(await within(dialog).findByText('Indique o nome.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Nome')).toHaveAttribute('aria-invalid', 'true');
    expect(requests).toEqual([]);
  });

  it("shows the server's validation error on its field", async () => {
    const state = terminalsServer();
    createHandler(state, () =>
      problem(400, 'VALIDATION_FAILED', 'Invalid request', {
        errors: [{ field: 'name', message: 'O nome tem caracteres inválidos.' }],
      }),
    );

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user);

    expect(await within(dialog).findByText('O nome tem caracteres inválidos.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Nome')).toHaveAttribute('aria-invalid', 'true');
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'false');
  });

  it('shows a taken code (TERMINAL_CODE_TAKEN) on the code field', async () => {
    const state = terminalsServer([caixa1]);
    createHandler(state, () =>
      problem(409, 'TERMINAL_CODE_TAKEN', 'Conflict', { detail: "Terminal code 'CX1' is already used in this store." }),
    );

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user, 'CX1');

    expect(await within(dialog).findByText('Já existe um terminal com este código nesta loja.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows any other coded 409 as a general error, not on the code field', async () => {
    const state = terminalsServer();
    createHandler(state, () => problem(409, 'VERSION_CONFLICT', 'Conflict'));

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user);

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Foi alterado por outra pessoa.');
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'false');
  });

  it('a request still in progress (IDEMPOTENCY_IN_PROGRESS) waits a second and is resent with the same key', async () => {
    const state = terminalsServer();
    const requests = createHandler(state, (n) => (n === 1 ? problem(409, 'IDEMPOTENCY_IN_PROGRESS') : undefined));

    const { user } = renderApp(TERMINALS);
    await fillAndSubmit(user);

    expect(await screen.findByRole('cell', { name: 'Caixa 2' })).toBeInTheDocument();
    expect(requests).toHaveLength(2);
    expect(requests[0]?.idempotencyKey).toEqual(A_KEY);
    expect(requests[1]).toEqual(requests[0]);
  });

  it('still in progress after three resends: the outcome is unknown, so "Tentar novamente" sends it again', async () => {
    const state = terminalsServer();
    const requests = createHandler(state, (n) => (n <= 4 ? problem(409, 'IDEMPOTENCY_IN_PROGRESS') : undefined));

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user);

    await user.click(await within(dialog).findByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('cell', { name: 'Caixa 2' })).toBeInTheDocument();
    expect(requests).toHaveLength(5);
    expect(requests[0]?.idempotencyKey).toEqual(A_KEY);
    // the resends and "Tentar novamente" all reuse the intent's one key
    expect(new Set(requests.map((request) => JSON.stringify(request))).size).toBe(1);
  }, 10_000);

  it('shows "Sem permissão" on a 403', async () => {
    const state = terminalsServer();
    createHandler(state, () => problem(403, 'HTTP_403', 'Forbidden'));

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user);

    expect(await within(dialog).findByText('Sem permissão para esta ação.')).toBeInTheDocument();
  });

  it('says "Sem ligação" when no answer came, and retries the same request once, with one effect', async () => {
    const state = terminalsServer();
    const requests = createHandler(state, (n) => (n === 1 ? HttpResponse.error() : undefined));

    const { user } = renderApp(TERMINALS);
    const dialog = await fillAndSubmit(user);

    expect(await within(dialog).findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('cell', { name: 'Caixa 2' })).toBeInTheDocument();
    expect(requests).toHaveLength(2);
    expect(requests[0]?.idempotencyKey).toEqual(A_KEY);
    expect(requests[1]).toEqual(requests[0]);
    expect(state.terminals).toHaveLength(1);
  });

  it('goes through step-up and, back on the resume page, finishes with the restored form, once', async () => {
    const state = terminalsServer();
    const requests = createHandler(state, (n) => (n === 1 ? stepUpRequired(300) : undefined));

    const first = renderApp(TERMINALS);
    await fillAndSubmit(first.user);

    const stepUp = await screen.findByRole('alertdialog', { name: 'Confirme a sua identidade' });
    await first.user.click(within(stepUp).getByRole('button', { name: 'Confirmar identidade' }));
    expect(first.leave).toHaveBeenCalledWith('/bff/step-up?acr_values=2&max_age=300&return=%2Fstep-up%2Fresume');
    expect(JSON.parse(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')!)).toMatchObject({
      method: 'POST',
      path: '/api/v1/backoffice/terminals',
      body: { storeId: baixa.id, code: 'CX2', name: 'Caixa 2', kind: 'POS' },
      // Not a secret: the resume repeats the request under the same key (FC §6).
      idempotencyKey: requests[0]!.idempotencyKey,
      returnTo: TERMINALS,
    });
    expect(requests[0]!.idempotencyKey).toEqual(A_KEY);

    newPageLoad();
    const back = renderApp('/step-up/resume');

    expect(await screen.findByRole('heading', { level: 1, name: 'Continuar a operação' })).toBeInTheDocument();
    expect(screen.getByLabelText('Código')).toHaveValue('CX2');
    expect(screen.getByLabelText('Nome')).toHaveValue('Caixa 2');
    await back.user.click(screen.getByRole('button', { name: 'Continuar' }));

    expect(await screen.findByRole('cell', { name: 'Caixa 2' })).toBeInTheDocument();
    expect(back.currentUrl()).toBe(TERMINALS);
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    expect(state.terminals).toHaveLength(1);
    expect(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')).toBeNull();
  });
});

describe('the one-time enrolment code', () => {
  function issueHandler(answer?: (n: number) => Response | undefined) {
    const requests: Seen[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/terminals/:id/enrollment-code'), async ({ request, params }) => {
        requests.push(await seen(request));
        return (
          answer?.(requests.length) ??
          HttpResponse.json(
            { terminalId: String(params.id), code: CODE, expiresAt: EXPIRES },
            { headers: { 'Cache-Control': 'no-store' } },
          )
        );
      }),
    );
    return requests;
  }

  it('is shown once, large and copyable, with its 24 h single-use note, and is gone after closing', async () => {
    terminalsServer([caixa1, bar]);
    const requests = issueHandler();

    const { user, queryClient, currentUrl } = renderApp(TERMINALS);
    await user.click(await screen.findByRole('button', { name: 'Emitir código para Bar' }));

    const dialog = await screen.findByRole('dialog', { name: 'Código de registo de Bar' });
    expect(within(dialog).getByText(GROUPED)).toBeInTheDocument();
    expect(within(dialog).getByText(/Válido durante 24 h e só pode ser usado uma vez/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Copiar código' }));
    expect(await navigator.clipboard.readText()).toBe(CODE);
    expect(within(dialog).getByText('Código copiado.')).toBeInTheDocument();
    expect(requests).toEqual([
      {
        url: `/api/v1/backoffice/terminals/${bar.id}/enrollment-code`,
        body: undefined,
        idempotencyKey: null,
        csrf: CSRF_TOKEN,
      },
    ]);

    await user.click(within(dialog).getByRole('button', { name: 'Fechar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(document.body.textContent).not.toContain(CODE);
    expect(document.body.textContent).not.toContain(GROUPED);
    // Never in storage, the address, or the query and mutation caches.
    expect(storedText()).not.toContain(CODE);
    expect(currentUrl()).not.toContain(CODE);
    await waitFor(() => {
      const cached = JSON.stringify([
        queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]),
        queryClient.getMutationCache().getAll().map((mutation) => mutation.state),
      ]);
      expect(cached).not.toContain(CODE);
    });
  });

  it('goes through step-up and resumes by repeating the request: one code, nothing secret stored', async () => {
    terminalsServer([caixa1, bar]);
    const requests = issueHandler((n) => (n === 1 ? stepUpRequired(300) : undefined));

    const first = renderApp(TERMINALS);
    await first.user.click(await screen.findByRole('button', { name: 'Emitir código para Bar' }));
    const stepUp = await screen.findByRole('alertdialog', { name: 'Confirme a sua identidade' });
    expect(within(stepUp).getByText(/segundo fator/)).toBeInTheDocument();
    await first.user.click(within(stepUp).getByRole('button', { name: 'Confirmar identidade' }));

    expect(first.leave).toHaveBeenCalledWith('/bff/step-up?acr_values=2&max_age=300&return=%2Fstep-up%2Fresume');
    expect(JSON.parse(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')!)).toMatchObject({
      method: 'POST',
      path: `/api/v1/backoffice/terminals/${bar.id}/enrollment-code`,
      idempotencyKey: null,
      returnTo: TERMINALS,
    });

    newPageLoad();
    const back = renderApp('/step-up/resume');

    expect(await screen.findByText('Emitir o código de registo do terminal Bar.')).toBeInTheDocument();
    await back.user.click(screen.getByRole('button', { name: 'Continuar' }));

    const dialog = await screen.findByRole('dialog', { name: 'Código de registo de Bar' });
    expect(within(dialog).getByText(GROUPED)).toBeInTheDocument();
    expect(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')).toBeNull();
    expect(storedText()).not.toContain(CODE);
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);

    await back.user.click(within(dialog).getByRole('button', { name: 'Fechar' }));
    await waitFor(() => expect(back.currentUrl()).toBe(TERMINALS));
    expect(document.body.textContent).not.toContain(GROUPED);
  });

  it('keeps the request to retry when no answer came, and repeats it once, never with a key', async () => {
    terminalsServer([bar]);
    const requests = issueHandler((n) => (n === 1 ? HttpResponse.error() : undefined));

    const { user } = renderApp(TERMINALS);
    await user.click(await screen.findByRole('button', { name: 'Emitir código para Bar' }));

    expect(await screen.findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByText(GROUPED)).toBeInTheDocument();
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    // The answer is the code: it must never be stored for a replay (ADR 0035), so no key goes with it.
    expect(requests.map((request) => request.idempotencyKey)).toEqual([null, null]);
  });
});

describe('revoking a terminal', () => {
  function revokeHandler(state: ReturnType<typeof terminalsServer>, answer?: (n: number) => Response | undefined) {
    const requests: Seen[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/terminals/:id/revoke'), async ({ request }) => {
        requests.push(await seen(request));
        const custom = answer?.(requests.length);
        if (custom) return custom;
        state.terminals = state.terminals.map((t) => (t.id === caixa1.id ? { ...t, status: 'REVOKED' } : t));
        return HttpResponse.json(state.terminals[0]);
      }),
    );
    return requests;
  }

  async function confirmRevoke(user: ReturnType<typeof renderApp>['user']) {
    await user.click(await screen.findByRole('button', { name: 'Revogar Caixa 1' }));
    const confirm = await screen.findByRole('alertdialog', { name: 'Revogar o terminal Caixa 1?' });
    await user.click(within(confirm).getByRole('button', { name: 'Revogar' }));
  }

  it('explains a terminal already revoked elsewhere (409 ILLEGAL_TERMINAL_STATE)', async () => {
    const state = terminalsServer([caixa1]);
    revokeHandler(state, () => problem(409, 'ILLEGAL_TERMINAL_STATE', 'Conflict'));

    const { user } = renderApp(TERMINALS);
    await confirmRevoke(user);

    expect(await screen.findByText('O terminal está revogado e não aceita esta ação.')).toBeInTheDocument();
  });

  it('retries with the same key when no answer came', async () => {
    const state = terminalsServer([caixa1]);
    const requests = revokeHandler(state, (n) => (n === 1 ? HttpResponse.error() : undefined));

    const { user } = renderApp(TERMINALS);
    await confirmRevoke(user);
    expect(await screen.findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('cell', { name: 'Revogado' })).toBeInTheDocument();
    expect(requests).toHaveLength(2);
    expect(requests[0]?.idempotencyKey).toEqual(A_KEY);
    expect(requests[1]).toEqual(requests[0]);
  });

  it('goes through step-up and resumes under the same key', async () => {
    const state = terminalsServer([caixa1]);
    const requests = revokeHandler(state, (n) => (n === 1 ? stepUpRequired(300) : undefined));

    const first = renderApp(TERMINALS);
    await confirmRevoke(first.user);
    const stepUp = await screen.findByRole('alertdialog', { name: 'Confirme a sua identidade' });
    await first.user.click(within(stepUp).getByRole('button', { name: 'Confirmar identidade' }));
    expect(JSON.parse(sessionStorage.getItem('restaurantpro.backoffice.pendingCommand')!)).toMatchObject({
      path: `/api/v1/backoffice/terminals/${caixa1.id}/revoke`,
      idempotencyKey: requests[0]!.idempotencyKey,
    });

    newPageLoad();
    const back = renderApp('/step-up/resume');
    expect(await screen.findByText('Revogar o terminal Caixa 1.')).toBeInTheDocument();
    await back.user.click(screen.getByRole('button', { name: 'Continuar' }));

    await waitFor(() => expect(back.currentUrl()).toBe(TERMINALS));
    expect(requests).toHaveLength(2);
    expect(requests[0]?.idempotencyKey).toEqual(A_KEY);
    expect(requests[1]).toEqual(requests[0]);
  });

  it('asks for confirmation first, then revokes once and shows the new state', async () => {
    const state = terminalsServer([caixa1]);
    const requests = revokeHandler(state);

    const { user } = renderApp(TERMINALS);
    await user.click(await screen.findByRole('button', { name: 'Revogar Caixa 1' }));
    let confirm = await screen.findByRole('alertdialog', { name: 'Revogar o terminal Caixa 1?' });
    await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }));
    expect(requests).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Revogar Caixa 1' }));
    confirm = await screen.findByRole('alertdialog', { name: 'Revogar o terminal Caixa 1?' });
    await user.click(within(confirm).getByRole('button', { name: 'Revogar' }));

    expect(await screen.findByRole('cell', { name: 'Revogado' })).toBeInTheDocument();
    expect(requests).toEqual([
      { url: `/api/v1/backoffice/terminals/${caixa1.id}/revoke`, body: undefined, idempotencyKey: A_KEY, csrf: CSRF_TOKEN },
    ]);
  });
});

describe('the step-up resume page', () => {
  const KEY = 'restaurantpro.backoffice.pendingCommand';
  const valid = {
    method: 'POST',
    path: `/api/v1/backoffice/terminals/${bar.id}/revoke`,
    body: null,
    idempotencyKey: 'dddddddd-0000-4000-8000-000000000004',
    returnTo: TERMINALS,
    label: 'Bar',
    tenantId: admin.tenantId,
  };

  it.each([
    ['garbage', 'not json {'],
    ['an expired command', JSON.stringify({ ...valid, savedAt: Date.now() - 16 * 60_000 })],
    ['a command of another company', JSON.stringify({ ...valid, tenantId: 'cccccccc-0000-4000-8000-000000000003', savedAt: Date.now() })],
    ['a path that is not a resumable command', JSON.stringify({ ...valid, path: '/api/v1/backoffice/users', savedAt: Date.now() })],
    ['a revoke stored without its key', JSON.stringify({ ...valid, idempotencyKey: null, savedAt: Date.now() })],
    ['a code issue stored with a key', JSON.stringify({ ...valid, path: `/api/v1/backoffice/terminals/${bar.id}/enrollment-code`, savedAt: Date.now() })],
    ['a return outside the app', JSON.stringify({ ...valid, returnTo: '//evil.example', savedAt: Date.now() })],
    ['a return with a backslash', JSON.stringify({ ...valid, returnTo: '/\\evil', savedAt: Date.now() })],
    ['a return with an escaped tab', JSON.stringify({ ...valid, returnTo: '/\\t/evil', savedAt: Date.now() })],
    ['a return with a control character', JSON.stringify({ ...valid, returnTo: '/\t/evil', savedAt: Date.now() })],
  ])('discards %s safely', async (_name, stored) => {
    terminalsServer([bar]);
    let revokes = 0;
    server.use(http.post(api('/api/v1/backoffice/terminals/:id/revoke'), () => {
      revokes += 1;
      return HttpResponse.json(bar);
    }));
    sessionStorage.setItem(KEY, stored);

    renderApp('/step-up/resume');

    expect(await screen.findByText('Não há nenhuma operação por continuar.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continuar' })).not.toBeInTheDocument();
    expect(sessionStorage.getItem(KEY)).toBeNull();
    expect(revokes).toBe(0);
  });

  it.each([
    ['a code issue', `/api/v1/backoffice/terminals/${bar.id}/enrollment-code`, null, null, 'Emitir o código de registo do terminal Bar.'],
    ['a revoke', `/api/v1/backoffice/terminals/${bar.id}/revoke`, null, valid.idempotencyKey, 'Revogar o terminal Bar.'],
    ['a registration', '/api/v1/backoffice/terminals', { storeId: baixa.id, code: 'CX2', name: 'Bar', kind: 'POS' }, valid.idempotencyKey, 'Registar terminal'],
  ])('forgets %s as soon as "Continuar" sends it, so a reload cannot replay it', async (_name, path, body, key, shown) => {
    terminalsServer([bar]);
    const storedWhenSent: (string | null)[] = [];
    const keysSent: (string | null)[] = [];
    server.use(
      http.post(api('/api/v1/backoffice/terminals*'), ({ request }) => {
        storedWhenSent.push(sessionStorage.getItem(KEY));
        keysSent.push(request.headers.get('Idempotency-Key'));
        return HttpResponse.error();
      }),
    );
    sessionStorage.setItem(KEY, JSON.stringify({ ...valid, path, body, idempotencyKey: key, savedAt: Date.now() }));

    const { user } = renderApp('/step-up/resume');
    expect(await screen.findByText(shown)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continuar' }));

    // No answer: the stored command is already gone, the restored context stays for a manual retry.
    expect(await screen.findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    expect(storedWhenSent).toEqual([null]);
    expect(sessionStorage.getItem(KEY)).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    await waitFor(() => expect(storedWhenSent).toEqual([null, null]));
    // The stored key (none for the code issue) goes with "Continuar" and with every retry.
    expect(keysSent).toEqual([key, key]);
  });

  it('lets the user cancel the pending command', async () => {
    terminalsServer([bar]);
    sessionStorage.setItem(KEY, JSON.stringify({ ...valid, savedAt: Date.now() }));

    const { user, currentUrl } = renderApp('/step-up/resume');

    expect(await screen.findByText('Revogar o terminal Bar.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));

    await waitFor(() => expect(currentUrl()).toBe(TERMINALS));
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });
});
