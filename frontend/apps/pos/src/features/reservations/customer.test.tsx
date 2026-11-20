import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { id } from '../../test/table-map';
import {
  ANA_DETAILS,
  atReservations,
  BOOKED,
  bookEndpoint,
  CANDIDATE,
  createCustomerEndpoint,
  CUSTOMER_ID,
  customerEndpoint,
  customersEndpoint,
  json,
  RESERVATION_PERMISSIONS,
} from '../../test/reservations';

type User = Awaited<ReturnType<typeof atReservations>>['user'];

async function newReservation(user: User) {
  await user.click(await screen.findByRole('button', { name: 'Nova reserva' }));
  const form = await screen.findByRole('dialog', { name: 'Nova reserva' });
  await user.type(within(form).getByLabelText('Hora'), '20:30');
  await user.click(within(form).getByRole('button', { name: '2 pessoas' }));
  return form;
}

async function search(user: User, form: HTMLElement, text: string) {
  await user.type(within(form).getByRole('searchbox', { name: 'Procurar cliente' }), text);
  await user.click(within(form).getByRole('button', { name: 'Procurar' }));
}

describe("a reservation's customer", () => {
  it('is found by name or phone and linked: the contact comes from the record, the request names it', async () => {
    const searched = customersEndpoint(json([CANDIDATE]));
    customerEndpoint(CUSTOMER_ID, json(ANA_DETAILS));
    const booked = bookEndpoint(json(BOOKED));
    const { user } = await atReservations();

    const form = await newReservation(user);
    await search(user, form, 'ana');
    expect(new URL(searched[0]!.url).searchParams.get('q')).toBe('ana');
    const results = within(form).getByRole('list', { name: 'Clientes encontrados' });
    expect(results).toHaveTextContent('Ana Ferreira');
    expect(results).toHaveTextContent('841234567');
    await user.click(within(results).getByRole('button', { name: 'Associar Ana Ferreira' }));

    expect(within(form).getByText('Cliente associado:')).toBeInTheDocument();
    expect(within(form).getByRole('textbox', { name: 'Nome' })).toHaveValue('Ana Ferreira');
    expect(within(form).getByRole('textbox', { name: 'Telefone' })).toHaveValue('841234567');
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    await waitFor(() => expect(booked).toHaveLength(1));
    expect(booked[0]!.body).toMatchObject({ customerId: CUSTOMER_ID, contactName: 'Ana Ferreira', contactPhone: '841234567' });
  });

  it('says when no customer matches, and can be unlinked again', async () => {
    customersEndpoint(json([]), json([CANDIDATE]));
    customerEndpoint(CUSTOMER_ID, json(ANA_DETAILS));
    const booked = bookEndpoint(json(BOOKED));
    const { user } = await atReservations();

    const form = await newReservation(user);
    await search(user, form, 'zé');
    expect(await within(form).findByText('Nenhum cliente encontrado.')).toBeInTheDocument();

    await user.clear(within(form).getByRole('searchbox', { name: 'Procurar cliente' }));
    await search(user, form, 'ana');
    await user.click(await within(form).findByRole('button', { name: 'Associar Ana Ferreira' }));
    await user.click(within(form).getByRole('button', { name: 'Desassociar cliente' }));
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    await waitFor(() => expect(booked).toHaveLength(1));
    expect(booked[0]!.body.customerId).toBeUndefined();
  });

  it('a new customer is created from the name and phone typed, once, and linked', async () => {
    const createdCustomer = createCustomerEndpoint(json({ ...ANA_DETAILS, id: id(402), name: 'Rui Sousa' }));
    customerEndpoint(id(402), json({ ...ANA_DETAILS, id: id(402), name: 'Rui Sousa' }));
    const booked = bookEndpoint(json(BOOKED));
    const { user } = await atReservations();

    const form = await newReservation(user);
    const create = within(form).getByRole('button', { name: 'Criar ficha de cliente' });
    expect(create).toBeDisabled();
    await user.type(within(form).getByRole('textbox', { name: 'Nome' }), 'Rui Sousa');
    await user.type(within(form).getByRole('textbox', { name: 'Telefone' }), '84 555 1234');
    await user.click(create);

    expect(await within(form).findByText('Cliente associado:')).toBeInTheDocument();
    expect(createdCustomer).toHaveLength(1);
    expect(createdCustomer[0]!.body).toEqual({ name: 'Rui Sousa', phone: '84 555 1234' });
    expect(createdCustomer[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(within(form).queryByRole('button', { name: 'Criar ficha de cliente' })).not.toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    await waitFor(() => expect(booked).toHaveLength(1));
    expect(booked[0]!.body.customerId).toBe(id(402));
  });

  it('a search that fails says so and leaves the form usable', async () => {
    customersEndpoint(() => HttpResponse.error());
    const { user } = await atReservations();

    const form = await newReservation(user);
    await search(user, form, 'ana');

    expect(await within(form).findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível procurar clientes.');
    await user.type(within(form).getByRole('textbox', { name: 'Nome' }), 'Ana');
    expect(within(form).getByRole('button', { name: 'Guardar reserva' })).toBeEnabled();
  });

  it('without the customer permissions the reservation keeps its contact only', async () => {
    const { user } = await atReservations({
      permissions: RESERVATION_PERMISSIONS.filter((permission) => !permission.startsWith('PERM_CUSTOMER_')),
    });

    const form = await newReservation(user);

    expect(within(form).queryByRole('searchbox', { name: 'Procurar cliente' })).not.toBeInTheDocument();
    expect(within(form).queryByRole('button', { name: 'Criar ficha de cliente' })).not.toBeInTheDocument();
    expect(within(form).getByRole('textbox', { name: 'Nome' })).toBeInTheDocument();
  });
});
