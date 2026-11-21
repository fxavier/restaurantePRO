import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, problem } from '../../test/fixtures';
import { CUSTOMERS, customersServer, customerUrl, lusitania, type Customer } from '../../test/customers-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const DETAILS = `/api/v1/backoffice/customers/${lusitania.id}`;

describe('customer data', () => {
  it('shows who the customer is in the Dados tab', async () => {
    customersServer();

    renderApp(customerUrl(lusitania.id));

    const detail = await screen.findByRole('region', { name: lusitania.name });
    const data = within(detail).getByRole('tabpanel', { name: 'Dados' });
    expect(within(data).getByText('Notas').nextSibling).toHaveTextContent('Fatura mensal');
    expect(within(data).getByText('Estado').nextSibling).toHaveTextContent('Ativo');
  });

  it('edits the data with the version it was read at, and shows what the server saved', async () => {
    const { requests } = customersServer();
    let current: Customer = lusitania;
    server.use(
      http.put(api(DETAILS), async ({ request }) => {
        const body = (await request.json()) as Partial<Customer>;
        current = { ...current, ...body, version: (current.version ?? 0) + 1 };
        return HttpResponse.json(current);
      }),
      http.get(api(DETAILS), () => HttpResponse.json(current)),
    );
    const { user } = renderApp(customerUrl(lusitania.id));
    const detail = await screen.findByRole('region', { name: lusitania.name });

    await user.click(within(detail).getByRole('button', { name: 'Editar dados' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar dados do cliente' });
    const email = within(dialog).getByLabelText('Email');
    await user.clear(email);
    await user.type(email, 'contas@lusitania.co.mz');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const data = within(detail).getByRole('tabpanel', { name: 'Dados' });
    expect(await within(data).findByText('contas@lusitania.co.mz')).toBeInTheDocument();
    const put = requests.find((r) => r.method === 'PUT');
    expect(put?.body).toEqual({
      name: lusitania.name,
      taxId: lusitania.taxId,
      email: 'contas@lusitania.co.mz',
      notes: lusitania.notes,
      active: true,
      version: 3,
    });
    expect(put?.idempotencyKey).toBeNull();
  });

  it('says another person changed the customer meanwhile, and reads it again', async () => {
    customersServer();
    let reads = 0;
    server.use(
      http.put(api(DETAILS), () => problem(409, 'VERSION_CONFLICT')),
      http.get(api(DETAILS), () => HttpResponse.json(++reads === 1 ? lusitania : { ...lusitania, version: 4, notes: 'Pagamento a 30 dias' })),
    );
    const { user } = renderApp(customerUrl(lusitania.id));
    const detail = await screen.findByRole('region', { name: lusitania.name });

    await user.click(within(detail).getByRole('button', { name: 'Editar dados' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar dados do cliente' });
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByText('Foi alterado por outra pessoa. Recarregue e tente de novo.')).toBeInTheDocument();
    expect(await within(detail).findByText('Pagamento a 30 dias')).toBeInTheDocument();
  });

  it('creates a customer under one key and opens it', async () => {
    const { requests } = customersServer();
    const created: Customer = { id: 'c0000000-0000-4000-8000-000000000009', name: 'Eventos & Catering Douro', active: true, version: 0, phones: [], addresses: [] };
    server.use(
      http.post(api('/api/v1/backoffice/customers'), () => HttpResponse.json(created)),
      http.get(api(`/api/v1/backoffice/customers/${created.id}`), () => HttpResponse.json(created)),
    );
    const { user, currentUrl } = renderApp(CUSTOMERS);

    await user.click(await screen.findByRole('button', { name: 'Novo cliente' }));
    const dialog = await screen.findByRole('dialog', { name: 'Novo cliente' });
    await user.type(within(dialog).getByLabelText('Nome'), 'Eventos & Catering Douro');
    await user.type(within(dialog).getByLabelText('Telefone'), '+258 84 000 1111');
    await user.click(within(dialog).getByRole('button', { name: 'Criar cliente' }));

    expect(await screen.findByRole('region', { name: created.name })).toBeInTheDocument();
    expect(currentUrl()).toBe(customerUrl(created.id));
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.body).toEqual({ name: 'Eventos & Catering Douro', phone: '+258 84 000 1111' });
    expect(post?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('shows no create or edit to a user who may only view customers', async () => {
    customersServer({ session: admin });

    renderApp(customerUrl(lusitania.id));

    const detail = await screen.findByRole('region', { name: lusitania.name });
    expect(within(detail).queryByRole('button', { name: 'Editar dados' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Novo cliente' })).not.toBeInTheDocument();
  });
});
