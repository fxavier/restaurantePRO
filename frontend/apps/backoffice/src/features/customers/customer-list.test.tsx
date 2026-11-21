import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { CUSTOMERS, customersServer, lusitaniaDebt, tejoDebt } from '../../test/customers-fixtures';
import { api } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const DEBTS = '/api/v1/backoffice/customers/debts';

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

  it('says when nobody owes', async () => {
    customersServer();
    server.use(http.get(api(DEBTS), () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0 })));

    renderApp(CUSTOMERS);

    expect(await screen.findByText('Nenhum cliente tem saldo devedor.')).toBeInTheDocument();
  });

  it('pages through the customers who owe', async () => {
    const { requests } = customersServer();
    server.use(
      http.get(api(DEBTS), ({ request }) =>
        HttpResponse.json({
          content: [new URL(request.url).searchParams.get('page') === '1' ? tejoDebt : lusitaniaDebt],
          page: 0,
          size: 20,
          totalElements: 21,
        }),
      ),
    );
    const { user } = renderApp(CUSTOMERS);

    await screen.findByRole('button', { name: 'Lusitânia Tech, Lda.' });
    expect(screen.getByText('Página 1 de 2')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));

    expect(await screen.findByRole('button', { name: 'Construtora Tejo & Filhos, SA' })).toBeInTheDocument();
    expect(requests.some((r) => r.path === DEBTS && r.search === '?page=1&size=20')).toBe(true);
  });

  it('says the service cannot be reached and reads again on request', async () => {
    customersServer();
    let calls = 0;
    server.use(
      http.get(api(DEBTS), () =>
        ++calls <= 3 ? HttpResponse.error() : HttpResponse.json({ content: [lusitaniaDebt], page: 0, size: 20, totalElements: 1 }),
      ),
    );
    const { user } = renderApp(CUSTOMERS);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('button', { name: 'Lusitânia Tech, Lda.' })).toBeInTheDocument();
  });

  it('says when a search finds nobody, and goes back to the debts when cleared', async () => {
    customersServer();
    server.use(http.get(api('/api/v1/backoffice/customers'), () => HttpResponse.json([])));
    const { user } = renderApp(CUSTOMERS);

    await user.type(await screen.findByRole('searchbox', { name: 'Pesquisar cliente' }), 'Zeca{Enter}');
    expect(await screen.findByText('Nenhum cliente encontrado.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Limpar pesquisa' }));

    expect(await screen.findByRole('table', { name: 'Clientes com saldo devedor' })).toBeInTheDocument();
  });

  it('asks for a longer text instead of sending a search the server would refuse', async () => {
    const { requests } = customersServer();
    const { user } = renderApp(CUSTOMERS);

    await user.type(await screen.findByRole('searchbox', { name: 'Pesquisar cliente' }), 'Lu{Enter}');

    expect(await screen.findByText('Escreva pelo menos 3 letras ou 4 algarismos.')).toBeInTheDocument();
    expect(requests.filter((r) => r.path === '/api/v1/backoffice/customers')).toEqual([]);
  });
});
