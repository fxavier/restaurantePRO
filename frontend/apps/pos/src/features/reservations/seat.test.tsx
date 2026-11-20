import { screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { catalogEndpoint, created, NEW_ORDER, NEW_ORDER_ID, orderEndpoint, json as orderJson } from '../../test/order';
import { problem } from '../../test/server';
import { id, MAP } from '../../test/table-map';
import { atReservations, BOOKED, DAY, json, row, seatEndpoint } from '../../test/reservations';

const seatDialog = () => screen.findByRole('dialog', { name: 'Sentar Ana Ferreira' });

async function openSeat(user: Awaited<ReturnType<typeof atReservations>>['user']) {
  await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Sentar' }));
  return seatDialog();
}

describe('seating a reservation', () => {
  it("opens the reservation's free table for its party, linked to it, and lands in the new order", async () => {
    const seated = seatEndpoint(id(1), created(NEW_ORDER));
    orderEndpoint(NEW_ORDER_ID, orderJson(NEW_ORDER));
    catalogEndpoint();
    const { user, router } = await atReservations();

    const dialog = await openSeat(user);
    expect(within(dialog).getByText('4 pessoas')).toBeInTheDocument();
    expect(within(dialog).getByRole('radio', { name: /^M1/ })).toBeChecked();
    // Only free, unlocked tables can be opened: M2 and M3 are occupied.
    expect(within(dialog).getAllByRole('radio').map((radio) => radio.getAttribute('value'))).toEqual([
      id(1),
      id(4),
      id(10),
    ]);
    await user.click(within(dialog).getByRole('button', { name: 'Sentar' }));

    await screen.findByRole('heading', { name: 'Mesa M1' });
    expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`);
    expect(seated).toHaveLength(1);
    expect(seated[0]!.body).toEqual({ guests: 4, reservationId: BOOKED.id });
    expect(seated[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('another free table can be chosen, from the detail panel too', async () => {
    const seated = seatEndpoint(id(10), created(NEW_ORDER));
    orderEndpoint(NEW_ORDER_ID, orderJson(NEW_ORDER));
    catalogEndpoint();
    const { user } = await atReservations();

    await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' }));
    const panel = screen.getByRole('complementary', { name: 'Detalhe da reserva' });
    await user.click(within(panel).getByRole('button', { name: 'Sentar' }));
    const dialog = await seatDialog();
    await user.click(within(dialog).getByRole('radio', { name: /^E1/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Sentar' }));

    await screen.findByRole('heading', { name: 'Mesa M1' });
    expect(seated[0]!.body).toEqual({ guests: 4, reservationId: BOOKED.id });
  });

  it('a reservation already seated elsewhere is refused, and the day is read again', async () => {
    seatEndpoint(id(1), () => problem(409, 'RESERVATION_NOT_BOOKED'));
    const { user, days } = await atReservations({ day: [json(DAY)] });

    const dialog = await openSeat(user);
    const reads = days.length;
    await user.click(within(dialog).getByRole('button', { name: 'Sentar' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'A reserva já não está por chegar: foi sentada, cancelada ou dada como não comparência.',
    );
    await expect.poll(() => days.length).toBeGreaterThan(reads);
  });

  it('a table taken meanwhile is refused with the reason', async () => {
    seatEndpoint(id(1), () => problem(409, 'TABLE_OCCUPIED'));
    const { user } = await atReservations();

    const dialog = await openSeat(user);
    await user.click(within(dialog).getByRole('button', { name: 'Sentar' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A mesa acabou de ser aberta noutro terminal.');
  });

  it('an unknown outcome is retried with the same key, never as a new seating', async () => {
    const seated = seatEndpoint(id(1), () => HttpResponse.error(), created(NEW_ORDER));
    orderEndpoint(NEW_ORDER_ID, orderJson(NEW_ORDER));
    catalogEndpoint();
    const { user, router } = await atReservations();

    const dialog = await openSeat(user);
    await user.click(within(dialog).getByRole('button', { name: 'Sentar' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Sem ligação — não se sabe se a mesa foi aberta.');
    // The choice is locked while the outcome is unknown: a retry is the same intent.
    expect(within(dialog).getByRole('radio', { name: /^E1/ })).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await screen.findByRole('heading', { name: 'Mesa M1' });
    expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`);
    expect(seated).toHaveLength(2);
    expect(seated[1]!.idempotencyKey).toBe(seated[0]!.idempotencyKey);
  });

  it('a day without a free table says so, and offers nothing to confirm', async () => {
    const full = {
      ...MAP,
      areas: MAP.areas.map((area) => ({
        ...area,
        tables: area.tables.map((table) => ({ ...table, status: 'OCCUPIED' as const })),
      })),
    };
    const { user } = await atReservations({ map: full });

    const dialog = await openSeat(user);

    expect(await within(dialog).findByText('Não há mesas livres. Liberte uma mesa no mapa de sala.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Sentar' })).toBeDisabled();
  });
});
