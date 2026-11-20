import { screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa, problem, sommerschield, stepUpRequired } from '../../test/fixtures';
import { empregadoSala, gerente, joao, maria, peopleHandlers } from '../../test/people-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

function peopleServer(...args: Parameters<typeof peopleHandlers>) {
  const { state, handlers } = peopleHandlers(...args);
  server.use(...handlers);
  return state;
}

type State = ReturnType<typeof peopleServer>;

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
