import { screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { api, baixa, problem } from '../../test/fixtures';
import { diningServer, m1, m2, reservation } from '../../test/dining-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';
import { dateIn } from './dates';

const DINING = `/organization/dining?storeId=${baixa.id}`;

describe('dining settings', () => {
  it("shows the store's reservation window and no-show grace and saves them at the version read", async () => {
    const state = diningServer();
    const { user } = renderApp(DINING);

    const card = await screen.findByRole('region', { name: 'Reservas: definições' });
    const window = await within(card).findByLabelText('Janela de reserva (minutos)');
    await waitFor(() => expect(window).toHaveValue(120));
    expect(within(card).getByLabelText('Tolerância de não comparência (minutos)')).toHaveValue(30);
    await user.clear(window);
    await user.type(window, '90');
    await user.click(within(card).getByRole('button', { name: 'Guardar definições' }));

    expect(await within(card).findByText('Definições guardadas.')).toBeInTheDocument();
    expect(state.writes.map(({ method, path, body }) => ({ method, path, body }))).toEqual([
      {
        method: 'PUT',
        path: `/api/v1/backoffice/dining/settings/${baixa.id}`,
        body: { reservationWindowMinutes: 90, noShowGraceMinutes: 30, version: 2 },
      },
    ]);
  });

  it('refuses a window outside 1 to 1440 minutes before sending', async () => {
    const state = diningServer();
    const { user } = renderApp(DINING);

    const card = await screen.findByRole('region', { name: 'Reservas: definições' });
    const window = await within(card).findByLabelText('Janela de reserva (minutos)');
    await waitFor(() => expect(window).toHaveValue(120));
    await user.clear(window);
    await user.type(window, '0');
    await user.click(within(card).getByRole('button', { name: 'Guardar definições' }));

    expect(await within(card).findByText('Indique um número inteiro de minutos, de 1 a 1440.')).toBeInTheDocument();
    expect(state.writes).toEqual([]);
  });
});

describe("the day's reservations", () => {
  it("lists today's reservations of the store, in the store's timezone, with their tables and state", async () => {
    const state = diningServer({
      reservations: [
        reservation('c1c1c1c1-0000-4000-8000-000000000001'),
        reservation('c2c2c2c2-0000-4000-8000-000000000002', {
          contactName: 'Carlos Nhantumbo',
          partySize: 2,
          startsAt: '2026-11-20T19:00:00Z',
          status: 'CANCELLED',
          tableIds: [m1.id!, m2.id!],
        }),
      ],
    });

    renderApp(DINING);

    const table = await screen.findByRole('table', { name: 'Reservas do dia' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([
      ['20:30', 'Joana Mabote', '4', 'M01', 'Reservada'],
      ['21:00', 'Carlos Nhantumbo', '2', 'M01, M02', 'Cancelada'],
    ]);
    const today = dateIn('Africa/Maputo', new Date());
    expect(screen.getByLabelText('Dia')).toHaveValue(today);
    expect(state.reservationQueries).toEqual([`?storeId=${baixa.id}&from=${today}&to=${today}`]);
  });

  it('reads another day when the user picks it, and says when it has none', async () => {
    const state = diningServer();
    const { user } = renderApp(DINING);

    expect(await screen.findByText('Sem reservas neste dia.')).toBeInTheDocument();
    const day = screen.getByLabelText('Dia');
    await user.clear(day);
    await user.type(day, '2026-12-24');

    await waitFor(() => expect(state.reservationQueries).toContain(`?storeId=${baixa.id}&from=2026-12-24&to=2026-12-24`));
  });

  it('says so when the user may not see the reservations', async () => {
    diningServer();
    server.use(http.get(api('/api/v1/backoffice/reservations'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp(DINING);

    expect(await screen.findByText('Sem permissão para ver as reservas desta loja.')).toBeInTheDocument();
  });
});
