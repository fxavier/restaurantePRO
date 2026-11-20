import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CUSTOMERS, customersServer } from '../../test/customers-fixtures';
import { renderApp } from '../../test/render-app';

describe('customers list', () => {
  it('opens on the customers who owe, each with the balance the server computed', async () => {
    customersServer();

    renderApp(CUSTOMERS);

    const table = await screen.findByRole('table', { name: 'Clientes com saldo devedor' });
    expect(within(table).getByRole('row', { name: /Lusitânia Tech/ })).toHaveTextContent('450,20 MTn');
    expect(within(table).getByRole('row', { name: /Construtora Tejo/ })).toHaveTextContent(
      /620,00 MTn.*0,00 MTn.*400,00 MTn.*0,00 MTn.*220,00 MTn/,
    );
  });

  it('searches by name or phone and lists the candidates with their tax id and phones', async () => {
    const { requests } = customersServer();
    const { user } = renderApp(CUSTOMERS);

    await user.type(await screen.findByRole('searchbox', { name: 'Pesquisar cliente' }), 'Lus{Enter}');

    const results = await screen.findByRole('table', { name: 'Resultados da pesquisa' });
    const row = within(results).getByRole('row', { name: /Lusitânia Tech/ });
    expect(row).toHaveTextContent('501234567');
    expect(row).toHaveTextContent('258841234567');
    expect(requests.some((r) => r.path === '/api/v1/backoffice/customers' && r.search === '?q=Lus')).toBe(true);
  });

  it('asks for a longer text instead of sending a search the server would refuse', async () => {
    const { requests } = customersServer();
    const { user } = renderApp(CUSTOMERS);

    await user.type(await screen.findByRole('searchbox', { name: 'Pesquisar cliente' }), 'Lu{Enter}');

    expect(await screen.findByText('Escreva pelo menos 3 letras ou 4 algarismos.')).toBeInTheDocument();
    expect(requests.filter((r) => r.path === '/api/v1/backoffice/customers')).toEqual([]);
  });
});
