import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, problem } from '../../test/fixtures';
import { customersServer, customerUrl, lusitania, type Customer } from '../../test/customers-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const BASE = `/api/v1/backoffice/customers/${lusitania.id}`;

async function openContacts() {
  const app = renderApp(customerUrl(lusitania.id));
  const detail = await screen.findByRole('region', { name: lusitania.name });
  await app.user.click(within(detail).getByRole('tab', { name: 'Moradas & Contactos' }));
  return { ...app, panel: within(detail).getByRole('tabpanel', { name: 'Moradas & Contactos' }) };
}

/** The customer as the server keeps it, changed by the phone and address commands. */
function contactsServer() {
  let current: Customer = lusitania;
  server.use(
    http.get(api(BASE), () => HttpResponse.json(current)),
    http.post(api(`${BASE}/phones`), async ({ request }) => {
      const body = (await request.json()) as { number: string; label?: string; primary?: boolean };
      const phone = { id: 'p0000000-0000-4000-8000-000000000002', digits: body.number.replace(/\D/g, ''), label: body.label, primary: false };
      current = { ...current, phones: [...(current.phones ?? []), phone] };
      return HttpResponse.json(phone);
    }),
    http.delete(api(`${BASE}/phones/:phoneId`), ({ params }) => {
      current = { ...current, phones: current.phones?.filter((p) => p.id !== params.phoneId) };
      return new HttpResponse(null, { status: 204 });
    }),
    http.post(api(`${BASE}/addresses`), async ({ request }) => {
      const body = (await request.json()) as Record<string, string>;
      const address = { id: 'a0000000-0000-4000-8000-000000000002', version: 0, ...body };
      current = { ...current, addresses: [...(current.addresses ?? []), address] };
      return HttpResponse.json(address);
    }),
    http.put(api(`${BASE}/addresses/:addressId`), async ({ params, request }) => {
      const body = (await request.json()) as Record<string, string | number>;
      const address = { ...body, id: params.addressId as string, version: Number(body.version) + 1 };
      current = { ...current, addresses: current.addresses?.map((a) => (a.id === params.addressId ? address : a)) };
      return HttpResponse.json(address);
    }),
  );
}

describe('addresses and contacts', () => {
  it('lists the phones, the primary one marked, and the addresses by kind', async () => {
    customersServer();

    const { panel } = await openContacts();

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const phones = within(panel).getByRole('list', { name: 'Telefones' });
    expect(within(phones).getByRole('listitem')).toHaveTextContent(/258841234567.*Escritório.*Principal/);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const addresses = within(panel).getByRole('list', { name: 'Moradas' });
    expect(within(addresses).getByRole('listitem')).toHaveTextContent(
      /Faturação.*Av\. Julius Nyerere 102.*4\.º andar.*1100 Maputo.*Moçambique/,
    );
  });

  it('adds a phone under one key and lists what the server kept', async () => {
    const { requests } = customersServer();
    contactsServer();
    const { panel, user } = await openContacts();

    await user.click(within(panel).getByRole('button', { name: 'Adicionar telefone' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar telefone' });
    await user.type(within(dialog).getByLabelText('Número'), '+258 82 765 4321');
    await user.type(within(dialog).getByLabelText('Etiqueta'), 'Contabilidade');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const phones = within(panel).getByRole('list', { name: 'Telefones' });
    await waitFor(() => expect(within(phones).getAllByRole('listitem')).toHaveLength(2));
    expect(within(phones).getAllByRole('listitem')[1]).toHaveTextContent(/258827654321.*Contabilidade/);
    const post = requests.find((r) => r.path.endsWith('/phones') && r.method === 'POST');
    expect(post?.body).toEqual({ number: '+258 82 765 4321', label: 'Contabilidade', primary: false });
    expect(post?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('says the customer already has the phone', async () => {
    customersServer();
    server.use(http.post(api(`${BASE}/phones`), () => problem(409, 'PHONE_EXISTS')));
    const { panel, user } = await openContacts();

    await user.click(within(panel).getByRole('button', { name: 'Adicionar telefone' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar telefone' });
    await user.type(within(dialog).getByLabelText('Número'), '258841234567');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));

    expect(await within(dialog).findByText('O cliente já tem este telefone.')).toBeInTheDocument();
  });

  it('removes a phone once confirmed', async () => {
    const { requests } = customersServer();
    contactsServer();
    const { panel, user } = await openContacts();

    await user.click(within(panel).getByRole('button', { name: 'Remover 258841234567' }));
    const confirm = await screen.findByRole('alertdialog', { name: 'Remover o telefone 258841234567?' });
    await user.click(within(confirm).getByRole('button', { name: 'Remover' }));

    expect(await within(panel).findByText('Sem telefones.')).toBeInTheDocument();
    expect(requests.filter((r) => r.method === 'DELETE').map((r) => r.path)).toEqual([
      `${BASE}/phones/p0000000-0000-4000-8000-000000000001`,
    ]);
  });

  it('adds a delivery address under one key', async () => {
    const { requests } = customersServer();
    contactsServer();
    const { panel, user } = await openContacts();

    await user.click(within(panel).getByRole('button', { name: 'Adicionar morada' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar morada' });
    await user.selectOptions(within(dialog).getByLabelText('Tipo'), 'DELIVERY');
    await user.type(within(dialog).getByLabelText('Morada'), 'Rua da Sé 12');
    await user.type(within(dialog).getByLabelText('Cidade'), 'Maputo');
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const addresses = within(panel).getByRole('list', { name: 'Moradas' });
    await waitFor(() => expect(within(addresses).getAllByRole('listitem')).toHaveLength(2));
    expect(within(addresses).getAllByRole('listitem')[1]).toHaveTextContent(/Entrega.*Rua da Sé 12.*Maputo/);
    const post = requests.find((r) => r.path.endsWith('/addresses'));
    expect(post?.body).toEqual({ kind: 'DELIVERY', line1: 'Rua da Sé 12', city: 'Maputo' });
    expect(post?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('edits an address with the version it was read at, and lists what the server saved', async () => {
    const { requests } = customersServer();
    contactsServer();
    const { panel, user } = await openContacts();

    await user.click(within(panel).getByRole('button', { name: 'Editar morada Av. Julius Nyerere 102' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar morada' });
    expect(within(dialog).getByLabelText('Morada')).toHaveValue('Av. Julius Nyerere 102');
    await user.clear(within(dialog).getByLabelText('Cidade'));
    await user.type(within(dialog).getByLabelText('Cidade'), 'Matola');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const addresses = within(panel).getByRole('list', { name: 'Moradas' });
    await waitFor(() => expect(within(addresses).getByRole('listitem')).toHaveTextContent(/Matola/));
    const put = requests.find((r) => r.method === 'PUT');
    expect(put?.path).toBe(`${BASE}/addresses/a0000000-0000-4000-8000-000000000001`);
    expect(put?.body).toMatchObject({ kind: 'BILLING', line1: 'Av. Julius Nyerere 102', city: 'Matola', version: 0 });
  });

  it('says the address changed meanwhile when the server refuses a stale version', async () => {
    customersServer();
    server.use(http.put(api(`${BASE}/addresses/:addressId`), () => problem(409, 'VERSION_CONFLICT')));
    const { panel, user } = await openContacts();

    await user.click(within(panel).getByRole('button', { name: 'Editar morada Av. Julius Nyerere 102' }));
    const dialog = await screen.findByRole('dialog', { name: 'Editar morada' });
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
  });

  it('shows no change to a user who may only view customers', async () => {
    customersServer({ session: admin });

    const { panel } = await openContacts();

    expect(within(panel).getByRole('list', { name: 'Telefones' })).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Adicionar telefone' })).not.toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: /Remover/ })).not.toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Adicionar morada' })).not.toBeInTheDocument();
  });
});
