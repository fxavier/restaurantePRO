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
});
