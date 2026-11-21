import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { api, problem } from '../../test/fixtures';
import { CUSTOMERS, customersServer, customerUrl, lusitania } from '../../test/customers-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

describe('customer detail', () => {
  it('opens the customer picked in the list, and keeps it in the address', async () => {
    customersServer();
    const { user, currentUrl } = renderApp(CUSTOMERS);

    const table = await screen.findByRole('table', { name: 'Clientes com saldo devedor' });
    await user.click(within(table).getByRole('button', { name: 'Lusitânia Tech, Lda.' }));

    const detail = await screen.findByRole('region', { name: 'Lusitânia Tech, Lda.' });
    expect(detail).toHaveTextContent('NIF 501234567');
    expect(detail).toHaveTextContent('financeiro@lusitania.co.mz');
    expect(within(detail).getByRole('tab', { name: 'Dados' })).toHaveAttribute('aria-selected', 'true');
    for (const tab of ['Moradas & Contactos', 'Crédito', 'Fidelização']) {
      expect(within(detail).getByRole('tab', { name: tab })).toBeInTheDocument();
    }
    expect(currentUrl()).toBe(customerUrl(lusitania.id));
  });

  it('says so when the customer does not exist', async () => {
    customersServer();
    server.use(http.get(api(`/api/v1/backoffice/customers/${lusitania.id}`), () => problem(404, 'NOT_FOUND')));

    renderApp(customerUrl(lusitania.id));

    expect(await screen.findByText('Cliente não encontrado.')).toBeInTheDocument();
  });

  it('offers to try again when the server cannot be reached', async () => {
    customersServer();
    let calls = 0;
    server.use(
      http.get(api(`/api/v1/backoffice/customers/${lusitania.id}`), () =>
        ++calls <= 3 ? HttpResponse.error() : HttpResponse.json(lusitania),
      ),
    );
    const { user } = renderApp(customerUrl(lusitania.id));

    const detail = await screen.findByRole('region', { name: 'Cliente' });
    expect(await within(detail).findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(within(detail).getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('region', { name: 'Lusitânia Tech, Lda.' })).toBeInTheDocument();
  });
});
