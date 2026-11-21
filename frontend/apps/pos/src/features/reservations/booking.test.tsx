import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/server';
import { id } from '../../test/table-map';
import { atReservations, BOOKED, bookEndpoint, DAY, json } from '../../test/reservations';

type User = Awaited<ReturnType<typeof atReservations>>['user'];

const dialog = () => screen.findByRole('dialog', { name: 'Nova reserva' });

async function fillIn(user: User) {
  const form = await dialog();
  await user.type(within(form).getByLabelText('Hora'), '20:30');
  await user.click(within(form).getByRole('button', { name: '4 pessoas' }));
  await user.click(within(form).getByRole('checkbox', { name: /^M1/ }));
  await user.type(within(form).getByRole('textbox', { name: 'Nome' }), 'Rui Sousa');
  await user.type(within(form).getByRole('textbox', { name: 'Telefone' }), '84 555 1234');
  await user.type(within(form).getByRole('textbox', { name: 'Notas' }), 'Aniversário');
  return form;
}

describe('booking a reservation', () => {
  it("books the day shown at a store time, for a party, with its tables and contact", async () => {
    const booked = bookEndpoint(json({ ...BOOKED, id: id(399), contactName: 'Rui Sousa' }));
    const { user, days } = await atReservations();

    await user.click(await screen.findByRole('button', { name: 'Nova reserva' }));
    const form = await fillIn(user);
    expect(within(form).getByRole('button', { name: 'Hoje' })).toHaveAttribute('aria-pressed', 'true');
    const reads = days.length;
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(booked).toHaveLength(1);
    expect(booked[0]!.body).toEqual({
      startsAt: '2026-11-20T18:30:00.000Z',
      partySize: 4,
      contactName: 'Rui Sousa',
      contactPhone: '84 555 1234',
      notes: 'Aniversário',
      tableIds: [id(1)],
    });
    expect(booked[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(days.length).toBeGreaterThan(reads));
  });

  it('can be for tomorrow, another party size, several tables or none', async () => {
    const booked = bookEndpoint(json(BOOKED));
    const { user } = await atReservations();

    await user.click(await screen.findByRole('button', { name: 'Nova reserva' }));
    const form = await dialog();
    await user.click(within(form).getByRole('button', { name: 'Amanhã' }));
    expect(within(form).getByLabelText('Data')).toHaveValue('2026-11-21');
    await user.type(within(form).getByLabelText('Hora'), '13:00');
    await user.type(within(form).getByRole('spinbutton', { name: 'Número de pessoas' }), '15');
    await user.click(within(form).getByRole('checkbox', { name: /^M1/ }));
    await user.click(within(form).getByRole('checkbox', { name: /^E1/ }));
    await user.click(within(form).getByRole('checkbox', { name: /^M1/ }));
    await user.type(within(form).getByRole('textbox', { name: 'Nome' }), 'Grupo Escola');
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    await waitFor(() => expect(booked).toHaveLength(1));
    expect(booked[0]!.body).toEqual({
      startsAt: '2026-11-21T11:00:00.000Z',
      partySize: 15,
      contactName: 'Grupo Escola',
      tableIds: [id(10)],
    });
  });

  it('cannot be saved without a time, a party and a name', async () => {
    const { user } = await atReservations();

    await user.click(await screen.findByRole('button', { name: 'Nova reserva' }));
    const form = await dialog();
    const save = within(form).getByRole('button', { name: 'Guardar reserva' });
    expect(save).toBeDisabled();
    await user.type(within(form).getByLabelText('Hora'), '20:30');
    await user.click(within(form).getByRole('button', { name: '2 pessoas' }));
    expect(save).toBeDisabled();
    await user.type(within(form).getByRole('textbox', { name: 'Nome' }), 'Rui');
    expect(save).toBeEnabled();
  });

  it("a time already past is refused with the server's reason, and the input stays", async () => {
    bookEndpoint(() => problem(422, 'RESERVATION_IN_THE_PAST'));
    const { user } = await atReservations();

    await user.click(await screen.findByRole('button', { name: 'Nova reserva' }));
    const form = await fillIn(user);
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    expect(await within(form).findByRole('alert')).toHaveTextContent('Essa hora já passou. Escolha uma hora futura.');
    expect(within(form).getByRole('textbox', { name: 'Nome' })).toHaveValue('Rui Sousa');
  });

  it('an unknown outcome is retried with the same key and the same input, which is locked meanwhile', async () => {
    const booked = bookEndpoint(() => HttpResponse.error(), json(BOOKED));
    const { user } = await atReservations();

    await user.click(await screen.findByRole('button', { name: 'Nova reserva' }));
    const form = await fillIn(user);
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Sem ligação — não se sabe se a reserva ficou guardada.');
    expect(within(form).getByRole('textbox', { name: 'Nome' })).toHaveAttribute('readonly');
    await user.click(within(form).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(booked).toHaveLength(2);
    expect(booked[1]!.idempotencyKey).toBe(booked[0]!.idempotencyKey);
    expect(booked[1]!.body).toEqual(booked[0]!.body);
  });

  it('opens on the day shown, even an empty one', async () => {
    const booked = bookEndpoint(json(BOOKED));
    const { user } = await atReservations({ day: [json(DAY), json([])] });

    await user.click(await screen.findByRole('button', { name: 'Dia seguinte' }));
    await screen.findByText('Não há reservas neste dia.');
    await user.click(screen.getByRole('button', { name: 'Nova reserva' }));
    const form = await fillIn(user);
    expect(within(form).getByRole('button', { name: 'Amanhã' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(form).getByRole('button', { name: 'Guardar reserva' }));

    await waitFor(() => expect(booked).toHaveLength(1));
    expect(booked[0]!.body.startsAt).toBe('2026-11-21T18:30:00.000Z');
  });
});
