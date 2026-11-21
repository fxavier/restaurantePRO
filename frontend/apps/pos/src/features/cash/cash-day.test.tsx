import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atCash, cashPermissions, closeDayEndpoint, DAY, noOpenSession } from '../../test/cash';
import { problem } from '../../test/server';

const dayCard = () => screen.findByRole('region', { name: 'Fecho operacional do dia' });

/** The day as the server holds it, which the close's stand-in changes. */
function heldDay() {
  let current = DAY;
  return {
    answer: () => HttpResponse.json(current),
    close() {
      current = { ...DAY, status: 'OPERATIONALLY_CLOSED', version: 5 };
      return HttpResponse.json(current);
    },
  };
}

describe("the day's operational close", () => {
  it('a manager closes the business day after a confirmation, and sees it closed', async () => {
    const day = heldDay();
    const closed = closeDayEndpoint(day.close);
    const { user } = await atCash({
      permissions: cashPermissions('CASH_DAY_CLOSE'),
      session: [noOpenSession],
      day: [day.answer],
    });

    const card = await dayCard();
    expect(within(card).getByText('19/11/2026')).toBeInTheDocument();
    expect(within(card).getByText('Aberto')).toBeInTheDocument();
    await user.click(within(card).getByRole('button', { name: 'Fechar dia' }));
    const confirm = await screen.findByRole('alertdialog', { name: 'Fechar o dia 19/11/2026?' });
    await user.click(within(confirm).getByRole('button', { name: 'Confirmar fecho do dia' }));

    expect(await within(card).findByText('Fechado (operacional)')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Fechar dia' })).not.toBeInTheDocument();
    expect(closed[0]!.body).toEqual({ version: 4 });
    expect(closed[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('says why the server refuses to close the day', async () => {
    closeDayEndpoint(() => problem(422, 'OPEN_SESSIONS'));
    const { user } = await atCash({ permissions: cashPermissions('CASH_DAY_CLOSE'), session: [noOpenSession] });

    const card = await dayCard();
    await user.click(within(card).getByRole('button', { name: 'Fechar dia' }));
    await user.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Confirmar fecho do dia' }),
    );

    await waitFor(() =>
      expect(within(card).getByRole('alert')).toHaveTextContent('Ainda há sessões de caixa abertas neste dia.'),
    );
  });

  it('without the day-close permission shows the day but offers no close', async () => {
    await atCash({ session: [noOpenSession] });

    const card = await dayCard();
    expect(within(card).getByText('19/11/2026')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Fechar dia' })).not.toBeInTheDocument();
  });

  it('says so when the business day cannot be read', async () => {
    await atCash({ session: [noOpenSession], day: [() => problem(500, 'INTERNAL')] });

    expect(await screen.findByText('Não foi possível carregar o dia comercial. Erro inesperado (INTERNAL).')).toBeInTheDocument();
  });

  it('warns of an earlier business day still open', async () => {
    await atCash({
      session: [noOpenSession],
      day: [() => HttpResponse.json({ ...DAY, earlierOpenDays: [{ id: 'd', businessDate: '2026-11-18' }] })],
    });

    expect(await within(await dayCard()).findByText('O dia 18/11/2026 ainda está aberto.')).toBeInTheDocument();
  });
});
