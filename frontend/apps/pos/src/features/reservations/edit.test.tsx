import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { problem, SESSION } from '../../test/server';
import { id } from '../../test/table-map';
import {
  ANA_DETAILS,
  atReservations,
  BOOKED,
  CUSTOMER_ID,
  customerEndpoint,
  DAY,
  json,
  RESERVATION,
  RESERVATION_PERMISSIONS,
  row,
  updateEndpoint,
} from '../../test/reservations';

type User = Awaited<ReturnType<typeof atReservations>>['user'];

async function editAna(user: User) {
  customerEndpoint(CUSTOMER_ID, json(ANA_DETAILS));
  await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' }));
  await user.click(
    within(screen.getByRole('complementary', { name: 'Detalhe da reserva' })).getByRole('button', { name: 'Editar reserva' }),
  );
  return screen.findByRole('dialog', { name: 'Editar a reserva de Ana Ferreira' });
}

describe('editing a reservation', () => {
  it('starts from the reservation as it is, and replaces it with the version read', async () => {
    const updated = updateEndpoint(RESERVATION.booked, json({ ...BOOKED, partySize: 6, version: 4 }));
    const { user } = await atReservations();

    const form = await editAna(user);
    expect(within(form).getByLabelText('Hora')).toHaveValue('19:30');
    expect(within(form).getByRole('button', { name: '4 pessoas' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(form).getByRole('checkbox', { name: /^M1/ })).toBeChecked();
    expect(within(form).getByRole('checkbox', { name: /^E1/ })).toBeChecked();
    expect(within(form).getByRole('textbox', { name: 'Nome' })).toHaveValue('Ana Ferreira');
    expect(await within(form).findByText('Ana Ferreira', { selector: 'span' })).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: '6 pessoas' }));
    await user.click(within(form).getByRole('checkbox', { name: /^E1/ }));
    await user.click(within(form).getByRole('button', { name: 'Guardar alterações' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(updated).toHaveLength(1);
    expect(updated[0]!.body).toEqual({
      startsAt: '2026-11-20T17:30:00.000Z',
      partySize: 6,
      contactName: 'Ana Ferreira',
      contactPhone: '841234567',
      notes: 'Cadeira de bebé',
      customerId: CUSTOMER_ID,
      tableIds: [id(1)],
      // A PUT replaces the whole reservation: the duration it was booked with is kept.
      durationMinutes: 120,
      version: 3,
    });
    // The contract has no Idempotency-Key on PUT: the version guards a repeat.
    expect(updated[0]!.idempotencyKey).toBeNull();
  });

  it('a reservation changed elsewhere is refused; the form restarts from the server and the stale edit is never sent', async () => {
    // Another terminal moved Ana to 21:00 (19:00Z).
    const changed = DAY.map((r) => (r.id === BOOKED.id ? { ...r, startsAt: '2026-11-20T19:00:00Z', version: 7 } : r));
    const updated = updateEndpoint(RESERVATION.booked, () => problem(409, 'VERSION_CONFLICT'), json(BOOKED));
    const { user } = await atReservations({ day: [json(DAY), json(changed)] });

    const form = await editAna(user);
    await user.click(within(form).getByRole('button', { name: '6 pessoas' }));
    await user.click(within(form).getByRole('button', { name: 'Guardar alterações' }));

    // The form is filled again from the list as read after the refusal: the dialog is queried anew.
    const edit = () => screen.getByRole('dialog', { name: 'Editar a reserva de Ana Ferreira' });
    await waitFor(() => expect(within(edit()).getByLabelText('Hora')).toHaveValue('21:00'));
    const fresh = edit();
    expect(within(fresh).getByRole('alert')).toHaveTextContent(
      'A reserva foi alterada noutro terminal. A lista foi atualizada: reveja e tente de novo.',
    );
    expect(within(fresh).getByRole('button', { name: '4 pessoas' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(fresh).getByRole('button', { name: 'Guardar alterações' }));

    await waitFor(() => expect(updated).toHaveLength(2));
    expect(updated[1]!.body).toMatchObject({ startsAt: '2026-11-20T19:00:00.000Z', partySize: 4, version: 7 });
  });

  it('a save before the list is read again sends the version the form was filled from, so it is refused again', async () => {
    const updated = updateEndpoint(RESERVATION.booked, () => problem(409, 'VERSION_CONFLICT'));
    // The list never shows the change: the form keeps the version it was filled from.
    const { user } = await atReservations();

    const form = await editAna(user);
    await user.click(within(form).getByRole('button', { name: 'Guardar alterações' }));
    expect(await within(form).findByRole('alert')).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Guardar alterações' }));

    await waitFor(() => expect(updated).toHaveLength(2));
    expect(updated[1]!.body.version).toBe(3);
  });
});

describe('the customer a reservation is linked to', () => {
  it('whose record cannot be read is still shown as linked, saying the record is unavailable', async () => {
    const { user } = await atReservations();
    customerEndpoint(CUSTOMER_ID, () => problem(404, 'HTTP_404'));
    await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' }));
    await user.click(
      within(screen.getByRole('complementary', { name: 'Detalhe da reserva' })).getByRole('button', { name: 'Editar reserva' }),
    );
    const form = await screen.findByRole('dialog', { name: 'Editar a reserva de Ana Ferreira' });

    expect(await within(form).findByText('Ficha de cliente indisponível')).toBeInTheDocument();
    expect(within(form).queryByText('…')).not.toBeInTheDocument();
  });

  it('is shown as linked, without reading the record, to who may not view customers', async () => {
    const { user } = await atReservations({
      permissions: RESERVATION_PERMISSIONS.filter((permission) => permission !== `PERM_CUSTOMER_VIEW@${SESSION.storeId}`),
    });
    const read = customerEndpoint(CUSTOMER_ID, json(ANA_DETAILS));
    await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' }));
    await user.click(
      within(screen.getByRole('complementary', { name: 'Detalhe da reserva' })).getByRole('button', { name: 'Editar reserva' }),
    );
    const form = await screen.findByRole('dialog', { name: 'Editar a reserva de Ana Ferreira' });

    expect(within(form).getByText('Cliente associado')).toBeInTheDocument();
    expect(within(form).queryByText('…')).not.toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Desassociar cliente' })).toBeInTheDocument();
    expect(read).toHaveLength(0);
  });
});
