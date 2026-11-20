import { screen, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem, SESSION } from '../../test/server';
import { atReservations, DAY, json, row, TODAY } from '../../test/reservations';

describe('the reservations of a day', () => {
  it("lists the store's reservations of today, read with the terminal token and the POS session", async () => {
    const { days } = await atReservations();

    const ana = await row('Ana Ferreira');
    expect(within(ana).getByText('19:30')).toBeInTheDocument();
    expect(within(ana).getByText('4 pessoas')).toBeInTheDocument();
    expect(within(ana).getByText('Por chegar')).toBeInTheDocument();
    expect(screen.getAllByRole('article').map((article) => article.getAttribute('aria-label'))).toEqual([
      'Pedro Miranda, 11:00',
      'Família Costa, 11:30',
      'Ana Ferreira, 19:30',
      'Carla Lopes, 20:00',
    ]);
    expect(new URL(days[0]!.url).searchParams.get('date')).toBe(TODAY);
    expect(days[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: SESSION.posSession });
    expect(screen.getByRole('heading', { name: 'Reservas' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Sexta-feira, 20 de novembro de 2026' })).toBeInTheDocument();
  });

  it('names the tables of a reservation by their codes on the map', async () => {
    await atReservations();

    expect(within(await row('Ana Ferreira')).getByText('M1 + E1')).toBeInTheDocument();
  });

  it('shows a labelled loading state while the day is read', async () => {
    await atReservations({ day: [() => delay('infinite').then(() => HttpResponse.json([]))] });

    expect(await screen.findByRole('status', { name: 'A carregar as reservas…' })).toBeInTheDocument();
  });

  it('says when the day has no reservations', async () => {
    await atReservations({ day: [json([])] });

    expect(await screen.findByText('Não há reservas neste dia.')).toBeInTheDocument();
  });

  it('says when the day could not be read, and reads it again on "Tentar novamente"', async () => {
    const { user, days } = await atReservations({ day: [() => problem(500, 'INTERNAL'), json(DAY)] });

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as reservas. Erro inesperado (INTERNAL).');
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await row('Ana Ferreira')).toBeInTheDocument();
    expect(days.length).toBeGreaterThanOrEqual(2);
  });

  it('says when the server cannot be reached', async () => {
    await atReservations({ day: [() => HttpResponse.error()] });

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível carregar as reservas.');
  });
});
