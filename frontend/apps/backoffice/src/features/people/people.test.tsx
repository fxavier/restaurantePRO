import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { peopleHandlers } from '../../test/people-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

function peopleServer(...args: Parameters<typeof peopleHandlers>) {
  const { state, handlers } = peopleHandlers(...args);
  server.use(...handlers);
  return state;
}

function bodyRows(table: HTMLElement) {
  return within(table).getAllByRole('row').slice(1);
}

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
});
