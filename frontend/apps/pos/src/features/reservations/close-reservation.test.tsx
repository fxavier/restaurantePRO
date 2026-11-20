import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/server';
import {
  atReservations,
  BOOKED,
  cancelEndpoint,
  DAY,
  json,
  noShowEndpoint,
  RESERVATION,
  row,
} from '../../test/reservations';

const CANCELLED_DAY = DAY.map((r) => (r.id === BOOKED.id ? { ...r, status: 'CANCELLED' as const, version: 4, cancelReason: 'Mudou de planos' } : r));
const NO_SHOW_DAY = DAY.map((r) => (r.id === BOOKED.id ? { ...r, status: 'NO_SHOW' as const, version: 4 } : r));

async function fromPanel(user: Awaited<ReturnType<typeof atReservations>>['user'], action: string) {
  await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' }));
  const panel = screen.getByRole('complementary', { name: 'Detalhe da reserva' });
  await user.click(within(panel).getByRole('button', { name: action }));
}

describe('cancelling a reservation', () => {
  it('asks for the reason, sends it with the version, and shows the day as the server has it', async () => {
    const cancelled = cancelEndpoint(RESERVATION.booked, json({ ...BOOKED, status: 'CANCELLED', version: 4 }));
    const { user } = await atReservations({ day: [json(DAY), json(CANCELLED_DAY)] });

    await fromPanel(user, 'Cancelar reserva');
    const dialog = await screen.findByRole('dialog', { name: 'Cancelar a reserva de Ana Ferreira' });
    expect(within(dialog).getByRole('button', { name: 'Cancelar reserva' })).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox', { name: 'Motivo' }), 'Mudou de planos');
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar reserva' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(cancelled).toHaveLength(1);
    expect(cancelled[0]!.body).toEqual({ reason: 'Mudou de planos', version: 3 });
    expect(cancelled[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(await within(await row('Ana Ferreira')).findByText('Cancelada')).toBeInTheDocument();
  });

  it('a reservation changed elsewhere is refused, the day read again, and the next attempt sends the new version', async () => {
    const changed = DAY.map((r) => (r.id === BOOKED.id ? { ...r, version: 5 } : r));
    const cancelled = cancelEndpoint(RESERVATION.booked, () => problem(409, 'VERSION_CONFLICT'), json({ ...BOOKED, version: 6 }));
    const { user } = await atReservations({ day: [json(DAY), json(changed)] });

    await fromPanel(user, 'Cancelar reserva');
    const dialog = await screen.findByRole('dialog', { name: 'Cancelar a reserva de Ana Ferreira' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Motivo' }), 'Mudou de planos');
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar reserva' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'A reserva foi alterada noutro terminal. A lista foi atualizada: reveja e tente de novo.',
    );
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar reserva' }));

    await waitFor(() => expect(cancelled).toHaveLength(2));
    expect(cancelled[1]!.body).toEqual({ reason: 'Mudou de planos', version: 5 });
    expect(cancelled[1]!.idempotencyKey).not.toBe(cancelled[0]!.idempotencyKey);
  });

  it('"Voltar" leaves the reservation as it was', async () => {
    const cancelled = cancelEndpoint(RESERVATION.booked, json(BOOKED));
    const { user } = await atReservations();

    await fromPanel(user, 'Cancelar reserva');
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Voltar' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(cancelled).toHaveLength(0);
  });
});

describe('recording a no-show', () => {
  it('confirms, sends the version, and shows the day as the server has it', async () => {
    const marked = noShowEndpoint(RESERVATION.booked, json({ ...BOOKED, status: 'NO_SHOW', version: 4 }));
    const { user } = await atReservations({ day: [json(DAY), json(NO_SHOW_DAY)] });

    await fromPanel(user, 'Registar não comparência');
    const dialog = await screen.findByRole('dialog', { name: 'Não comparência de Ana Ferreira' });
    await user.click(within(dialog).getByRole('button', { name: 'Registar não comparência' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(marked[0]!.body).toEqual({ version: 3 });
    expect(marked[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(await within(await row('Ana Ferreira')).findByText('Não compareceu')).toBeInTheDocument();
  });

  it('before its time the server refuses, and says why', async () => {
    noShowEndpoint(RESERVATION.booked, () => problem(422, 'RESERVATION_NOT_STARTED'));
    const { user } = await atReservations();

    await fromPanel(user, 'Registar não comparência');
    const dialog = await screen.findByRole('dialog', { name: 'Não comparência de Ana Ferreira' });
    await user.click(within(dialog).getByRole('button', { name: 'Registar não comparência' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'A reserva ainda não começou: só pode dar a não comparência depois da hora marcada.',
    );
  });

  it('an unknown outcome is retried with the same key', async () => {
    const marked = noShowEndpoint(RESERVATION.booked, () => HttpResponse.error(), json({ ...BOOKED, status: 'NO_SHOW' }));
    const { user } = await atReservations();

    await fromPanel(user, 'Registar não comparência');
    const dialog = await screen.findByRole('dialog', { name: 'Não comparência de Ana Ferreira' });
    await user.click(within(dialog).getByRole('button', { name: 'Registar não comparência' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Sem ligação — não se sabe se a não comparência ficou registada.',
    );
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(marked[1]!.idempotencyKey).toBe(marked[0]!.idempotencyKey);
  });
});
