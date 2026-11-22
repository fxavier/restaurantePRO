import { act, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it, onTestFinished, vi } from 'vitest';
import { atKitchen, byStation, kdsEndpoint, page, TICKET } from '../../test/kitchen';
import { renderPos } from '../../test/render-pos';
import { notificationsEndpoint, problem, token, tokenEndpoint } from '../../test/server';

const column = (name: string) => screen.findByRole('region', { name: new RegExp(`^${name}`) });

describe('the kitchen display', () => {
  it('shows the active rounds by state, each with its table, order, round, lines, quantities and notes', async () => {
    const reads = kdsEndpoint(page(TICKET.queued, TICKET.cooking, TICKET.ready));
    await atKitchen();

    const queued = await column('Na fila');
    const card = within(queued).getByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 2' });
    const [bife, polvo] = within(card).getAllByRole('listitem');
    expect(bife).toHaveTextContent(/^2×Bife da VaziaNota: mal passado$/);
    expect(polvo).toHaveTextContent(/^1×Polvo à Lagareiro1 x Batata a murro$/);
    expect(within(card).getByText('Cozinha')).toBeInTheDocument();
    expect(within(await column('Em preparação')).getByRole('article', { name: 'Mesa 08 - Pedido #4105, Ronda 1' })).toBeInTheDocument();
    expect(within(await column('Pronto')).getByRole('article', { name: 'Mesa 03 - Pedido #4112, Ronda 1' })).toBeInTheDocument();
    const query = new URL(reads[0]!.url).searchParams;
    expect(query.getAll('status')).toEqual(['QUEUED', 'IN_PREPARATION', 'READY']);
    expect(query.has('destination')).toBe(false);
  });

  it('shows how long each round has waited since it was queued', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-22T10:00:30Z') });
    onTestFinished(() => void vi.useRealTimers());
    kdsEndpoint(page(TICKET.queued, TICKET.cooking));
    await atKitchen();

    expect(await screen.findByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 2' })).toHaveTextContent('Em espera há 24 min');
    expect(screen.getByRole('article', { name: 'Mesa 08 - Pedido #4105, Ronda 1' })).toHaveTextContent('Em espera há 11 min');
  });

  it("filters by station: each station seen is offered with its count, and one chosen is read on its own", async () => {
    const reads = kdsEndpoint(byStation({ BAR: [TICKET.ready] }, [TICKET.queued, TICKET.cooking, TICKET.ready]));
    const { user } = await atKitchen();
    const stations = await screen.findByRole('group', { name: 'Estação' });
    expect(within(stations).getByRole('button', { name: 'Todas 3' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(stations).getByRole('button', { name: 'Cozinha 2' })).toHaveAttribute('aria-pressed', 'false');

    await user.click(within(stations).getByRole('button', { name: 'Bar 1' }));

    await waitFor(() => expect(screen.queryByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 2' })).not.toBeInTheDocument());
    expect(screen.getByRole('article', { name: 'Mesa 03 - Pedido #4112, Ronda 1' })).toBeInTheDocument();
    expect(within(stations).getByRole('button', { name: 'Bar 1' })).toHaveAttribute('aria-pressed', 'true');
    const query = new URL(reads.at(-1)!.url).searchParams;
    expect(query.get('destination')).toBe('BAR');
    expect(query.getAll('status')).toEqual(['QUEUED', 'IN_PREPARATION', 'READY']);
  });

  it('says so while it loads, then when no round is under way', async () => {
    let answer!: () => void;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    kdsEndpoint(async () => {
      await answered;
      return page()();
    });
    await atKitchen();

    expect(await screen.findByRole('status', { name: 'A carregar as rondas…' })).toBeInTheDocument();
    answer();
    expect(await screen.findByText('Nenhuma ronda em curso.')).toBeInTheDocument();
  });

  it('a refusal says what failed and reads the rounds again on "Tentar novamente"', async () => {
    const reads = kdsEndpoint(() => problem(500, 'INTERNAL'), page(TICKET.queued));
    const { user } = await atKitchen();

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as rondas. Erro inesperado (INTERNAL).');
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 2' })).toBeInTheDocument();
    expect(reads).toHaveLength(2);
  });

  it('without a connection it says the rounds could not be read', async () => {
    kdsEndpoint(() => HttpResponse.error());
    await atKitchen();

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível carregar as rondas.');
  });

  it('a PRODUCTION_TICKETS notice reads the rounds again; the notice itself is never shown', async () => {
    const { streams } = notificationsEndpoint('stream');
    kdsEndpoint(page(), page(TICKET.queued));
    await atKitchen();
    expect(await screen.findByText('Nenhuma ronda em curso.')).toBeInTheDocument();
    await waitFor(() => expect(streams.at(-1)?.closed).toBe(false));

    streams.at(-1)!.change('PRODUCTION_TICKETS', TICKET.queued.id);

    expect(await screen.findByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 2' })).toBeInTheDocument();
  });

  it('is not offered to a session without PRODUCTION_VIEW, and its address leads back to the tables', async () => {
    tokenEndpoint(token('jwt-1'));
    const { router } = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });

    const sections = await screen.findByRole('navigation', { name: 'Secções' });
    expect(within(sections).queryByRole('link', { name: 'KDS' })).not.toBeInTheDocument();
    await act(() => router.navigate('/kds'));
    expect(router.state.location.pathname).toBe('/tables');
  });
});
