import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa, problem, sommerschield, stepUpRequired } from '../../test/fixtures';
import {
  account,
  ana,
  empregadoSala,
  gerente,
  grant,
  joao,
  maria,
  page,
  peopleHandlers,
  suporte,
} from '../../test/people-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

function peopleServer(...args: Parameters<typeof peopleHandlers>) {
  const { state, handlers } = peopleHandlers(...args);
  server.use(...handlers);
  return state;
}

type State = ReturnType<typeof peopleServer>;

const A_KEY = expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

/** The GETs of one list, in order. */
function urls(state: State, path: string) {
  return state.requests.filter((r) => r.method === 'GET' && r.url.split('?')[0] === path).map((r) => r.url);
}

/** Everything the browser keeps that a script or a later visitor could read. */
function storedText() {
  return [localStorage, sessionStorage]
    .flatMap((storage) => Array.from({ length: storage.length }, (_, i) => storage.key(i)!))
    .map((key) => `${key}=${localStorage.getItem(key) ?? sessionStorage.getItem(key)}`)
    .join('\n');
}

function bodyRows(table: HTMLElement) {
  return within(table).getAllByRole('row').slice(1);
}

describe('people tabs', () => {
  it('opens the tab named by ?tab= and keeps the chosen store when switching tabs', async () => {
    peopleServer();

    const { user, currentUrl } = renderApp(`/people?storeId=${baixa.id}&tab=funcoes`);

    expect(await screen.findByRole('tab', { name: 'Funções', selected: true })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
      'Empregados',
      'Funções',
      'Utilizadores',
      'Acessos de suporte',
    ]);
    await user.click(screen.getByRole('tab', { name: 'Utilizadores' }));
    expect(screen.getByRole('tab', { name: 'Utilizadores', selected: true })).toBeInTheDocument();
    expect(currentUrl()).toBe(`/people?storeId=${baixa.id}&tab=utilizadores`);
  });

  it('shows only the tabs whose permission the user holds', async () => {
    peopleServer({ session: { ...admin, permissions: ['PERM_EMPLOYEE_MANAGE', 'PERM_ROLE_MANAGE'] } });

    renderApp('/people?tab=suporte');

    expect(await screen.findByRole('tab', { name: 'Empregados', selected: true })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Empregados', 'Funções']);
  });
});

describe('list states', () => {
  it('says so when there is nothing yet, and offers to create the first one', async () => {
    peopleServer({ roles: [] });

    renderApp('/people?tab=funcoes');

    expect(await screen.findByText('Ainda não há funções.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nova função' })).toBeEnabled();
  });

  it('shows "Sem ligação" with a retry when the list cannot be reached', async () => {
    peopleServer();
    let calls = 0;
    server.use(
      http.get(api('/api/v1/backoffice/employees'), () => {
        calls += 1;
        return calls <= 3 ? problem(503, 'BACKEND_UNAVAILABLE') : HttpResponse.json({ content: [maria], page: 0, size: 20, totalElements: 1 });
      }),
    );
    const { user } = renderApp('/people');

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Novo empregado' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('cell', { name: 'Maria Sitoe' })).toBeInTheDocument();
  });

  it("shows the API's refusal to read the list", async () => {
    peopleServer();
    server.use(http.get(api('/api/v1/backoffice/users'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp('/people?tab=utilizadores');

    expect(await screen.findByText('Sem permissão para ver as contas.')).toBeInTheDocument();
  });

  it('asks for a fresh second factor when reading the list needs it, and comes back to this tab', async () => {
    peopleServer();
    server.use(http.get(api('/api/v1/backoffice/support-grants'), () => stepUpRequired(120)));
    const { user, leave } = renderApp('/people?tab=suporte');

    expect(await screen.findByText('Para ver os acessos de suporte, confirme a sua identidade.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirmar identidade' }));

    expect(leave).toHaveBeenCalledWith('/bff/step-up?acr_values=2&max_age=120&return=%2Fpeople%3Ftab%3Dsuporte');
  });

  it('says so when the user holds none of the tabs’ permissions', async () => {
    peopleServer({ session: { ...admin, permissions: ['PERM_CASH_VIEW'] } });

    renderApp('/people');

    expect(await screen.findByText('Sem permissão para gerir empregados, funções ou utilizadores.')).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });
});

describe('employees tab', () => {
  it("lists the company's employees with their code and state, one server page at a time", async () => {
    const state = peopleServer();

    renderApp('/people');

    const table = await screen.findByRole('table', { name: 'Empregados' });
    expect(bodyRows(table).map((row) => within(row).getAllByRole('cell').slice(0, 3).map((cell) => cell.textContent))).toEqual([
      ['Maria Sitoe', 'EMP-01', 'Ativo'],
      ['João Macuácua', 'EMP-04', 'Bloqueado'],
      ['Rui Bila', 'EMP-11', 'Inativo'],
    ]);
    expect(state.requests.filter((r) => r.url.startsWith('/api/v1/backoffice/employees')).map((r) => r.url)).toEqual([
      '/api/v1/backoffice/employees?page=0&size=20',
    ]);
  });

  it('lists only the employees of the store chosen in the top bar', async () => {
    const state = peopleServer();

    renderApp(`/people?storeId=${baixa.id}`);

    await screen.findByRole('table', { name: 'Empregados' });
    expect(urls(state, '/api/v1/backoffice/employees')).toEqual([
      `/api/v1/backoffice/employees?storeId=${baixa.id}&page=0&size=20`,
    ]);
  });
});

describe('employee PIN', () => {
  it("resets an employee's PIN, which the POS asks them to change at the next sign-in", async () => {
    const state = peopleServer();
    const { user } = renderApp('/people');

    await user.click(await screen.findByRole('button', { name: 'Repor PIN de Maria Sitoe' }));
    const dialog = await screen.findByRole('dialog', { name: 'Repor o PIN de Maria Sitoe' });
    await user.type(within(dialog).getByLabelText('Novo PIN'), '908172');
    await user.click(within(dialog).getByRole('button', { name: 'Repor PIN' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.requests.filter((r) => r.method === 'POST')).toEqual([
      {
        method: 'POST',
        url: `/api/v1/backoffice/employees/${maria.id}/reset-pin`,
        body: { pin: '908172' },
        idempotencyKey: null,
      },
    ]);
    expect(await screen.findByRole('status')).toHaveTextContent('PIN de Maria Sitoe reposto.');
  });

  it('asks for a fresh second factor and comes back to this tab, keeping no PIN anywhere', async () => {
    peopleServer();
    server.use(http.post(api('/api/v1/backoffice/employees/:id/reset-pin'), () => stepUpRequired(300)));
    const { user, leave } = renderApp('/people?tab=empregados');

    await user.click(await screen.findByRole('button', { name: 'Repor PIN de Maria Sitoe' }));
    const dialog = await screen.findByRole('dialog', { name: 'Repor o PIN de Maria Sitoe' });
    await user.type(within(dialog).getByLabelText('Novo PIN'), '908172');
    await user.click(within(dialog).getByRole('button', { name: 'Repor PIN' }));
    const stepUp = await screen.findByRole('alertdialog', { name: 'Confirme a sua identidade' });
    await user.click(within(stepUp).getByRole('button', { name: 'Confirmar identidade' }));

    expect(leave).toHaveBeenCalledWith('/bff/step-up?acr_values=2&max_age=300&return=%2Fpeople%3Ftab%3Dempregados');
    expect(storedText()).not.toContain('908172');
  });

  it('unlocks only a locked employee', async () => {
    const state = peopleServer();
    const { user } = renderApp('/people');

    const table = await screen.findByRole('table', { name: 'Empregados' });
    expect(within(table).getAllByRole('button', { name: /^Desbloquear/ })).toHaveLength(1);
    await user.click(within(table).getByRole('button', { name: 'Desbloquear João Macuácua' }));

    const row = await screen.findByRole('row', { name: /João Macuácua/ });
    await waitFor(() => expect(within(row).getByText('Ativo')).toBeInTheDocument());
    expect(state.requests.filter((r) => r.method === 'POST').map((r) => r.url)).toEqual([
      `/api/v1/backoffice/employees/${joao.id}/unlock`,
    ]);
  });
});

describe('store assignments', () => {
  const atBaixa = { id: 'd1000000-0000-4000-8000-000000000001', employeeId: maria.id!, storeId: baixa.id, roleId: gerente.id! };

  it("shows an employee's stores and roles, adds one and removes one", async () => {
    const state = peopleServer({ assignments: [atBaixa] });
    const { user } = renderApp('/people');

    await user.click(await screen.findByRole('button', { name: 'Lojas e funções de Maria Sitoe' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lojas e funções de Maria Sitoe' });
    const list = await within(dialog).findByRole('list', { name: 'Atribuições' });
    expect(within(list).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      expect.stringContaining('Loja Baixa · Gerente'),
    ]);

    await user.selectOptions(within(dialog).getByLabelText('Loja'), 'Loja Sommerschield');
    await user.selectOptions(within(dialog).getByLabelText('Função'), 'Empregado de sala');
    await user.click(within(dialog).getByRole('button', { name: 'Atribuir' }));
    expect(await within(list).findByText('Loja Sommerschield · Empregado de sala')).toBeInTheDocument();

    await user.click(within(list).getByRole('button', { name: 'Retirar Gerente em Loja Baixa' }));
    await waitFor(() => expect(within(list).queryByText('Loja Baixa · Gerente')).not.toBeInTheDocument());
    expect(state.requests.filter((r) => r.method !== 'GET').map(({ method, url, body }) => ({ method, url, body }))).toEqual([
      {
        method: 'POST',
        url: `/api/v1/backoffice/employees/${maria.id}/assignments`,
        body: { storeId: sommerschield.id, roleId: empregadoSala.id },
      },
      { method: 'DELETE', url: `/api/v1/backoffice/employees/${maria.id}/assignments/${atBaixa.id}`, body: undefined },
    ]);
  });

  it('explains a role the employee already holds in that store', async () => {
    peopleServer({ assignments: [atBaixa] });
    server.use(
      http.post(api('/api/v1/backoffice/employees/:id/assignments'), () => problem(409, 'ROLE_ALREADY_ASSIGNED')),
    );
    const { user } = renderApp('/people');

    await user.click(await screen.findByRole('button', { name: 'Lojas e funções de Maria Sitoe' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lojas e funções de Maria Sitoe' });
    await user.selectOptions(within(dialog).getByLabelText('Loja'), 'Loja Baixa');
    await user.selectOptions(within(dialog).getByLabelText('Função'), 'Gerente');
    await user.click(within(dialog).getByRole('button', { name: 'Atribuir' }));

    expect(await within(dialog).findByText('O empregado já tem esta função nesta loja.')).toBeInTheDocument();
  });
});

describe('roles tab', () => {
  it('lists the roles with the permissions each holds', async () => {
    peopleServer();

    renderApp('/people?tab=funcoes');

    const table = await screen.findByRole('table', { name: 'Funções' });
    const [first, second] = bodyRows(table);
    expect(within(first!).getAllByRole('cell')[0]).toHaveTextContent('Gerente');
    expect(within(first!).getAllByRole('cell')[1]).toHaveTextContent('ORDER_VOID_APPROVECASH_VIEW');
    expect(within(second!).getAllByRole('cell')[0]).toHaveTextContent('Empregado de sala');
  });

  it('creates a role with the permissions added to it by name', async () => {
    const state = peopleServer();
    const { user } = renderApp('/people?tab=funcoes');

    await user.click(await screen.findByRole('button', { name: 'Nova função' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova função' });
    await user.type(within(dialog).getByLabelText('Nome'), 'Chefe de cozinha');
    await user.type(within(dialog).getByLabelText('Adicionar permissão'), ' production_view ');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));
    expect(within(dialog).getByRole('checkbox', { name: 'PRODUCTION_VIEW' })).toBeChecked();
    await user.click(within(dialog).getByRole('button', { name: 'Criar função' }));

    expect(await screen.findByRole('cell', { name: 'Chefe de cozinha' })).toBeInTheDocument();
    expect(state.requests.filter((r) => r.method === 'POST').map((r) => r.body)).toEqual([
      { name: 'Chefe de cozinha', permissions: ['PRODUCTION_VIEW'] },
    ]);
  });

  it('after a conflicting edit, loads the current version and saves against it', async () => {
    const state = peopleServer();
    const { user } = renderApp('/people?tab=funcoes');

    await user.click(await screen.findByRole('button', { name: 'Editar Gerente' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar a função Gerente' });
    // Someone else removes CASH_VIEW meanwhile.
    state.roles[0] = { ...gerente, permissions: ['ORDER_VOID_APPROVE'], version: 4 };
    await user.click(within(dialog).getByRole('checkbox', { name: 'ORDER_VOID_APPROVE' }));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByText('Foi alterado por outra pessoa. Recarregue e tente de novo.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Carregar a versão atual' }));
    expect(within(dialog).queryByRole('checkbox', { name: 'CASH_VIEW' })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('checkbox', { name: 'ORDER_VOID_APPROVE' })).toBeChecked();
    await user.click(within(dialog).getByRole('checkbox', { name: 'ORDER_VOID_APPROVE' }));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.requests.filter((r) => r.method === 'PUT').map((r) => r.body)).toEqual([
      { name: 'Gerente', permissions: ['CASH_VIEW'], version: 3 },
      { name: 'Gerente', permissions: [], version: 4 },
    ]);
  });
});

describe('accounts tab', () => {
  it('lists the BackOffice accounts with their roles, kind and state', async () => {
    peopleServer();

    renderApp('/people?tab=utilizadores');

    const table = await screen.findByRole('table', { name: 'Contas do BackOffice' });
    await within(table).findByText('Gerente');
    expect(bodyRows(table).map((row) => within(row).getAllByRole('cell').slice(0, 4).map((cell) => cell.textContent))).toEqual([
      ['ana.admin', 'Gerente', 'Conta', 'Ativo'],
      ['suporte.ops', '—', 'Suporte', 'Ativo'],
    ]);
  });

  it('opens an account linked to an employee, with its roles', async () => {
    const state = peopleServer();
    const { user } = renderApp('/people?tab=utilizadores');

    await user.click(await screen.findByRole('button', { name: 'Nova conta' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova conta' });
    await user.type(within(dialog).getByLabelText('Nome de utilizador'), 'carla.n');
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Empregado de sala' }));
    await user.selectOptions(await within(dialog).findByLabelText('Empregado'), 'Maria Sitoe');
    await user.click(within(dialog).getByRole('button', { name: 'Criar conta' }));

    expect(await screen.findByRole('cell', { name: 'carla.n' })).toBeInTheDocument();
    expect(state.requests.filter((r) => r.method === 'POST').map((r) => r.body)).toEqual([
      { username: 'carla.n', roleIds: [empregadoSala.id], support: false, employeeId: maria.id },
    ]);
  });

  it('shows a taken username on its field', async () => {
    peopleServer();
    server.use(http.post(api('/api/v1/backoffice/users'), () => problem(409, 'USERNAME_TAKEN')));
    const { user } = renderApp('/people?tab=utilizadores');

    await user.click(await screen.findByRole('button', { name: 'Nova conta' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova conta' });
    await user.type(within(dialog).getByLabelText('Nome de utilizador'), 'ana.admin');
    await user.click(within(dialog).getByRole('button', { name: 'Criar conta' }));

    expect(await within(dialog).findByText('Este nome de utilizador já está a ser utilizado.')).toBeInTheDocument();
  });

  it('deactivates an account against the version it was loaded from', async () => {
    const state = peopleServer();
    const { user } = renderApp('/people?tab=utilizadores');

    await user.click(await screen.findByRole('button', { name: 'Editar ana.admin' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar a conta ana.admin' });
    await user.click(within(dialog).getByRole('checkbox', { name: 'Conta ativa' }));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.requests.filter((r) => r.method === 'PUT').map(({ url, body }) => ({ url, body }))).toEqual([
      { url: `/api/v1/backoffice/users/${ana.id}`, body: { active: false, roleIds: [gerente.id], version: 0 } },
    ]);
  });
});

describe('support access tab', () => {
  const open = grant('f1000000-0000-4000-8000-000000000001', suporte.id!);

  it('lists the support grants with the account, reason and state', async () => {
    peopleServer({ grants: [open, grant('f1000000-0000-4000-8000-000000000002', suporte.id!, { status: 'EXPIRED', reason: 'Fecho' })] });

    renderApp('/people?tab=suporte');

    const table = await screen.findByRole('table', { name: 'Acessos de suporte' });
    expect(bodyRows(table).map((row) => [0, 1, 3].map((i) => within(row).getAllByRole('cell')[i]!.textContent))).toEqual([
      ['suporte.ops', 'Erro na impressão', 'Ativo'],
      ['suporte.ops', 'Fecho', 'Expirado'],
    ]);
  });

  it('grants access under one Idempotency-Key, sent again unchanged after "Sem ligação"', async () => {
    const state = peopleServer();
    let attempts = 0;
    server.use(
      http.post(api('/api/v1/backoffice/support-grants'), ({ request }) => {
        attempts += 1;
        if (attempts === 1) {
          state.requests.push({ method: 'POST', url: '/api/v1/backoffice/support-grants', body: 'lost', idempotencyKey: request.headers.get('Idempotency-Key') });
          return HttpResponse.error();
        }
        return undefined;
      }),
    );
    const { user } = renderApp('/people?tab=suporte');

    await user.click(await screen.findByRole('button', { name: 'Conceder acesso' }));
    const dialog = await screen.findByRole('dialog', { name: 'Conceder acesso de suporte' });
    await user.selectOptions(await within(dialog).findByLabelText('Conta de suporte'), 'suporte.ops');
    await user.type(within(dialog).getByLabelText('Identificador da pessoa de suporte'), 'ops-7f3a');
    await user.clear(within(dialog).getByLabelText('Duração (minutos)'));
    await user.type(within(dialog).getByLabelText('Duração (minutos)'), '90');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Impressora da cozinha');
    await user.click(within(dialog).getByRole('button', { name: 'Conceder' }));

    expect(await within(dialog).findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('cell', { name: 'Impressora da cozinha' })).toBeInTheDocument();
    const posts = state.requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]!.idempotencyKey).toEqual(A_KEY);
    expect(posts[1]).toEqual({
      method: 'POST',
      url: '/api/v1/backoffice/support-grants',
      body: { accountId: suporte.id, subject: 'ops-7f3a', durationMinutes: 90, reason: 'Impressora da cozinha' },
      idempotencyKey: posts[0]!.idempotencyKey,
    });
  });

  it('revokes an active grant once confirmed, with its own Idempotency-Key', async () => {
    const state = peopleServer({ grants: [open] });
    const { user } = renderApp('/people?tab=suporte');

    await user.click(await screen.findByRole('button', { name: 'Revogar o acesso de suporte.ops' }));
    const confirm = await screen.findByRole('alertdialog', { name: 'Revogar o acesso de suporte.ops?' });
    await user.click(within(confirm).getByRole('button', { name: 'Revogar' }));

    const row = await screen.findByRole('row', { name: /Erro na impressão/ });
    await waitFor(() => expect(within(row).getByText('Revogado')).toBeInTheDocument());
    expect(state.requests.filter((r) => r.method === 'POST')).toEqual([
      { method: 'POST', url: `/api/v1/backoffice/support-grants/${open.id}/revoke`, body: undefined, idempotencyKey: A_KEY },
    ]);
  });
});

describe('pickers', () => {
  /** A store manager: employees of one store, no company-wide role or account reads. */
  const storeManager = { ...admin, permissions: [`PERM_EMPLOYEE_MANAGE@${baixa.id}`] };

  it("says the roles cannot be read, instead of an empty 'Função' picker", async () => {
    peopleServer({ session: storeManager });
    server.use(http.get(api('/api/v1/backoffice/roles'), () => problem(403, 'HTTP_403', 'Forbidden')));
    const { user } = renderApp(`/people?storeId=${baixa.id}`);

    await user.click(await screen.findByRole('button', { name: 'Novo empregado' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo empregado' });

    expect(await within(dialog).findByText('Sem permissão para ver as funções.')).toBeInTheDocument();
  });

  it("offers to load the stores again when they cannot be reached, then picks one", async () => {
    peopleServer();
    let failing = true;
    server.use(
      http.get(api('/api/v1/backoffice/stores'), ({ request }) =>
        failing && new URL(request.url).searchParams.get('size') === '100' ? problem(503, 'BACKEND_UNAVAILABLE') : undefined,
      ),
    );
    const { user } = renderApp('/people');

    await user.click(await screen.findByRole('button', { name: 'Novo empregado' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo empregado' });
    expect(await within(dialog).findByText('Sem ligação: não foi possível carregar as lojas.')).toBeInTheDocument();
    failing = false;
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await user.selectOptions(within(dialog).getByLabelText('Loja'), 'Loja Baixa');
    expect(within(dialog).queryByText(/não foi possível carregar as lojas/)).not.toBeInTheDocument();
  });

  it("says the employees cannot be read when linking an account to one", async () => {
    peopleServer();
    server.use(
      http.get(api('/api/v1/backoffice/employees'), ({ request }) =>
        new URL(request.url).searchParams.get('size') === '100' ? problem(500, 'INTERNAL', 'Internal') : undefined,
      ),
    );
    const { user } = renderApp('/people?tab=utilizadores');

    await user.click(await screen.findByRole('button', { name: 'Nova conta' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nova conta' });

    expect(await within(dialog).findByText('Não foi possível carregar os empregados.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Tentar novamente' })).toBeEnabled();
  });

  it('says the support accounts cannot be read, on the tab and in the grant form', async () => {
    peopleServer({ session: { ...admin, permissions: ['PERM_SUPPORT_ACCESS_MANAGE'] } });
    server.use(http.get(api('/api/v1/backoffice/users'), () => problem(403, 'HTTP_403', 'Forbidden')));
    const { user } = renderApp('/people?tab=suporte');

    expect(await screen.findByText('Sem permissão para ver as contas de suporte.')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Conceder acesso' }));
    const dialog = await screen.findByRole('dialog', { name: 'Conceder acesso de suporte' });

    expect(await within(dialog).findByText('Sem permissão para ver as contas de suporte.')).toBeInTheDocument();
  });

  it('offers a support account that sorts after the first page of accounts', async () => {
    const others = Array.from({ length: 24 }, (_, i) =>
      account(`a3000000-0000-4000-8000-0000000000${String(i).padStart(2, '0')}`, `conta.${String(i).padStart(2, '0')}`),
    );
    const late = account('a3000000-0000-4000-8000-000000000099', 'zz.suporte', { support: true });
    peopleServer();
    server.use(
      http.get(api('/api/v1/backoffice/users'), ({ request }) => {
        const query = new URL(request.url).searchParams;
        const size = Number(query.get('size'));
        const all = [...others, late];
        return HttpResponse.json(page(all.slice(0, size), 0, size, all.length));
      }),
    );
    const { user } = renderApp('/people?tab=suporte');

    await user.click(await screen.findByRole('button', { name: 'Conceder acesso' }));
    const dialog = await screen.findByRole('dialog', { name: 'Conceder acesso de suporte' });

    await user.selectOptions(await within(dialog).findByLabelText('Conta de suporte'), 'zz.suporte');
    expect(within(dialog).getByLabelText('Conta de suporte')).toHaveValue(late.id);
  });
});

describe('new employee', () => {
  it('hires an employee with a PIN in a store and role, then shows them; the PIN is kept nowhere', async () => {
    const state = peopleServer();
    const { user } = renderApp(`/people?storeId=${baixa.id}`);

    await user.click(await screen.findByRole('button', { name: 'Novo empregado' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo empregado' });
    await user.type(within(dialog).getByLabelText('Código'), 'EMP-20');
    await user.type(within(dialog).getByLabelText('Nome'), 'Carla Nhaca');
    await user.type(within(dialog).getByLabelText('PIN inicial'), '4821');
    await user.selectOptions(within(dialog).getByLabelText('Função'), 'Gerente');
    expect(within(dialog).getByLabelText('Loja')).toHaveValue(baixa.id);
    await user.click(within(dialog).getByRole('button', { name: 'Criar empregado' }));

    expect(await screen.findByRole('cell', { name: 'Carla Nhaca' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Novo empregado' })).not.toBeInTheDocument();
    expect(state.requests.filter((r) => r.method === 'POST')).toEqual([
      {
        method: 'POST',
        url: '/api/v1/backoffice/employees',
        body: { code: 'EMP-20', name: 'Carla Nhaca', pin: '4821', storeId: baixa.id, roleId: gerente.id },
        idempotencyKey: null,
      },
    ]);
    expect(storedText()).not.toContain('4821');
  });

  it('checks the PIN before sending, and shows a taken code on its field', async () => {
    const state = peopleServer();
    server.use(
      http.post(api('/api/v1/backoffice/employees'), () => problem(409, 'EMPLOYEE_CODE_TAKEN', 'Employee code taken')),
    );
    const { user } = renderApp(`/people?storeId=${baixa.id}`);

    await user.click(await screen.findByRole('button', { name: 'Novo empregado' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo empregado' });
    await user.type(within(dialog).getByLabelText('Código'), 'EMP-01');
    await user.type(within(dialog).getByLabelText('Nome'), 'Outra Maria');
    await user.type(within(dialog).getByLabelText('PIN inicial'), '12');
    await user.selectOptions(within(dialog).getByLabelText('Função'), 'Gerente');
    await user.click(within(dialog).getByRole('button', { name: 'Criar empregado' }));

    expect(await within(dialog).findByText('O PIN tem de ter 4 a 8 algarismos.')).toBeInTheDocument();
    expect(state.requests.filter((r) => r.method === 'POST')).toEqual([]);

    await user.clear(within(dialog).getByLabelText('PIN inicial'));
    await user.type(within(dialog).getByLabelText('PIN inicial'), '1234');
    await user.click(within(dialog).getByRole('button', { name: 'Criar empregado' }));

    expect(await within(dialog).findByText('Já existe um empregado com este código.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'true');
  });
});
