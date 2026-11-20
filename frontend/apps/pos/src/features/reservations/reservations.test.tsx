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

  it('goes to another day and back to today', async () => {
    const { user, days } = await atReservations({ day: [json(DAY), json([])] });
    await row('Ana Ferreira');
    const dates = () => days.map((seen) => new URL(seen.url).searchParams.get('date'));

    await user.click(screen.getByRole('button', { name: 'Dia seguinte' }));
    expect(await screen.findByRole('heading', { name: 'Sábado, 21 de novembro de 2026' })).toBeInTheDocument();
    expect(await screen.findByText('Não há reservas neste dia.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Amanhã' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: 'Dia anterior' }));
    await user.click(screen.getByRole('button', { name: 'Dia anterior' }));
    expect(await screen.findByRole('heading', { name: 'Quinta-feira, 19 de novembro de 2026' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hoje' })).toHaveAttribute('aria-pressed', 'false');

    await user.click(screen.getByRole('button', { name: 'Hoje' }));
    expect(await screen.findByRole('heading', { name: 'Sexta-feira, 20 de novembro de 2026' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Amanhã' }));
    expect(await screen.findByRole('heading', { name: 'Sábado, 21 de novembro de 2026' })).toBeInTheDocument();
    expect(dates()).toEqual(expect.arrayContaining([TODAY, '2026-11-21', '2026-11-19']));
  });

  it('sums the day up by state, with the people of each', async () => {
    await atReservations();
    await row('Ana Ferreira');

    const summary = screen.getByRole('region', { name: 'Resumo do dia' });
    expect(within(summary).getByRole('group', { name: 'Total' })).toHaveTextContent(/4\s*reservas\s*·\s*14 pessoas/);
    expect(within(summary).getByRole('group', { name: 'Por chegar' })).toHaveTextContent(/1\s*reserva\s*·\s*4 pessoas/);
    expect(within(summary).getByRole('group', { name: 'Em sala' })).toHaveTextContent(/1\s*reserva\s*·\s*3 pessoas/);
    expect(within(summary).getByRole('group', { name: 'Não compareceram' })).toHaveTextContent(/1\s*reserva/);
    expect(within(summary).getByRole('group', { name: 'Canceladas' })).toHaveTextContent(/1\s*reserva/);
  });

  it('shows one state only, or the reservations whose name or phone matches the search', async () => {
    const { user } = await atReservations();
    await row('Ana Ferreira');
    const names = () => screen.queryAllByRole('article').map((article) => article.getAttribute('aria-label'));

    await user.click(screen.getByRole('button', { name: 'Sentadas (1)' }));
    expect(names()).toEqual(['Família Costa, 11:30']);
    expect(screen.getByRole('button', { name: 'Sentadas (1)' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: 'Todas (4)' }));
    await user.type(screen.getByRole('searchbox', { name: 'Procurar por nome ou telefone' }), '841 234');
    expect(names()).toEqual(['Ana Ferreira, 19:30']);

    await user.clear(screen.getByRole('searchbox', { name: 'Procurar por nome ou telefone' }));
    await user.type(screen.getByRole('searchbox', { name: 'Procurar por nome ou telefone' }), 'ninguém');
    expect(names()).toEqual([]);
    expect(screen.getByText('Nenhuma reserva corresponde à pesquisa.')).toBeInTheDocument();
  });

  it("a tapped reservation shows its detail, with the actions a booked one allows", async () => {
    const { user } = await atReservations();
    const panel = await screen.findByRole('complementary', { name: 'Detalhe da reserva' });
    expect(panel).toHaveTextContent('Escolha uma reserva para ver o detalhe.');

    await user.click(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' }));

    expect(within(panel).getByRole('heading', { name: 'Ana Ferreira' })).toBeInTheDocument();
    expect(panel).toHaveTextContent('Por chegar');
    expect(panel).toHaveTextContent('19:30');
    expect(panel).toHaveTextContent('4 pessoas');
    expect(panel).toHaveTextContent('M1 + E1');
    expect(panel).toHaveTextContent('841234567');
    expect(panel).toHaveTextContent('Cadeira de bebé');
    for (const action of ['Sentar', 'Editar reserva', 'Registar não comparência', 'Cancelar reserva']) {
      expect(within(panel).getByRole('button', { name: action })).toBeEnabled();
    }
    expect(within(await row('Ana Ferreira')).getByRole('button', { name: 'Ana Ferreira' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('a reservation that is no longer booked shows its outcome and no actions', async () => {
    const { user } = await atReservations();
    const panel = await screen.findByRole('complementary', { name: 'Detalhe da reserva' });

    await user.click(within(await row('Carla Lopes')).getByRole('button', { name: 'Carla Lopes' }));

    expect(panel).toHaveTextContent('Cancelada');
    expect(panel).toHaveTextContent('O cliente telefonou');
    expect(within(panel).queryAllByRole('button').map((button) => button.textContent)).toEqual([]);
    expect(within(await row('Carla Lopes')).queryByRole('button', { name: 'Sentar' })).not.toBeInTheDocument();
  });

  it('says when the server cannot be reached', async () => {
    await atReservations({ day: [() => HttpResponse.error()] });

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível carregar as reservas.');
  });
});
