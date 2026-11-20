import { screen, within } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa, problem } from '../../test/fixtures';
import { gerente, peopleHandlers } from '../../test/people-fixtures';
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
