import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atKitchen, kdsEndpoint, kitchenCommandEndpoint, page, TICKET, VIEWER } from '../../test/kitchen';
import { notificationsEndpoint, problem, SESSION } from '../../test/server';

const card = (name: string) => screen.findByRole('article', { name });
const QUEUED = 'Mesa 04 - Pedido #4092, Ronda 2';
const COOKING = 'Mesa 08 - Pedido #4105, Ronda 1';
const READY = 'Mesa 03 - Pedido #4112, Ronda 1';

const moved = (ticket: typeof TICKET.queued, status: typeof TICKET.queued.kitchen.status) => ({
  ...ticket,
  kitchen: { ...ticket.kitchen!, status, version: ticket.kitchen!.version! + 1 },
});

describe('moving a round on the kitchen display', () => {
  it('"Iniciar" starts a queued round at the version shown, with a key, and the column comes from the server', async () => {
    const started = moved(TICKET.queued, 'IN_PREPARATION');
    const reads = kdsEndpoint(page(TICKET.queued), page(started));
    const sent = kitchenCommandEndpoint(TICKET.queued.id!, 'start', () => HttpResponse.json(started));
    const { user } = await atKitchen();

    await user.click(within(await card(QUEUED)).getByRole('button', { name: 'Iniciar' }));

    const cooking = await screen.findByRole('region', { name: /^Em preparação/ });
    expect(await within(cooking).findByRole('article', { name: QUEUED })).toBeInTheDocument();
    expect(sent).toHaveLength(1);
    expect(sent[0]!.body).toEqual({ version: 0 });
    expect(sent[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(reads.length).toBeGreaterThanOrEqual(2);
  });

  it.each([
    [COOKING, TICKET.cooking, 'Marcar pronto', 'ready', 1],
    [READY, TICKET.ready, 'Retirar', 'bump', 2],
    [READY, TICKET.ready, 'Voltar à preparação', 'recall', 2],
  ] as const)('on "%s", "%s" sends %s at version %i', async (name, ticket, button, command, version) => {
    kdsEndpoint(page(TICKET.cooking, TICKET.ready));
    const sent = kitchenCommandEndpoint(ticket.id!, command, () => HttpResponse.json(ticket));
    const { user } = await atKitchen();

    await user.click(within(await card(name)).getByRole('button', { name: button }));

    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0]!.body).toEqual({ version });
  });

  it('a double tap sends one command', async () => {
    kdsEndpoint(page(TICKET.queued));
    const sent = kitchenCommandEndpoint(TICKET.queued.id!, 'start', async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
      return HttpResponse.json(moved(TICKET.queued, 'IN_PREPARATION'));
    });
    const { user } = await atKitchen();

    await user.dblClick(within(await card(QUEUED)).getByRole('button', { name: 'Iniciar' }));

    await expect.poll(() => sent.length).toBe(1);
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(sent).toHaveLength(1);
  });

  it('a command without an answer says so, and "Tentar novamente" resends it with the same key', async () => {
    kdsEndpoint(page(TICKET.queued));
    const sent = kitchenCommandEndpoint(
      TICKET.queued.id!,
      'start',
      () => HttpResponse.error(),
      () => HttpResponse.json(moved(TICKET.queued, 'IN_PREPARATION')),
    );
    const { user } = await atKitchen();

    await user.click(within(await card(QUEUED)).getByRole('button', { name: 'Iniciar' }));

    const queued = await card(QUEUED);
    expect(await within(queued).findByRole('alert')).toHaveTextContent('Sem ligação — a ronda não foi atualizada.');
    expect(within(queued).queryByRole('button', { name: 'Iniciar' })).not.toBeInTheDocument();
    await user.click(within(queued).getByRole('button', { name: 'Tentar novamente' }));

    await expect.poll(() => sent.length).toBe(2);
    expect(sent[1]!.idempotencyKey).toBe(sent[0]!.idempotencyKey);
    expect(sent[1]!.body).toEqual({ version: 0 });
  });

  it('a round moved on another screen is said above the board, which is read again', async () => {
    const reads = kdsEndpoint(page(TICKET.queued), page(moved(TICKET.queued, 'IN_PREPARATION')));
    kitchenCommandEndpoint(TICKET.queued.id!, 'start', () => problem(409, 'ILLEGAL_KITCHEN_STATE'));
    const { user } = await atKitchen();

    await user.click(within(await card(QUEUED)).getByRole('button', { name: 'Iniciar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('A ronda foi alterada noutro ecrã.');
    const cooking = await screen.findByRole('region', { name: /^Em preparação/ });
    expect(await within(cooking).findByRole('article', { name: QUEUED })).toBeInTheDocument();
    expect(reads.length).toBeGreaterThanOrEqual(2);
  });

  it('"Retirados" reads the latest rounds taken off, and "Repor" recalls one at the version shown', async () => {
    const reads = kdsEndpoint((_, seen) =>
      new URL(seen.url).searchParams.get('status') === 'BUMPED' ? page(TICKET.bumped)() : page(TICKET.queued)(),
    );
    const sent = kitchenCommandEndpoint(TICKET.bumped.id!, 'recall', () => HttpResponse.json(moved(TICKET.bumped, 'READY')));
    const { user } = await atKitchen();
    await card(QUEUED);

    await user.click(screen.getByRole('button', { name: 'Retirados' }));

    const bumped = await screen.findByRole('region', { name: 'Retirados' });
    const history = new URL(reads.at(-1)!.url).searchParams;
    expect(history.getAll('status')).toEqual(['BUMPED']);
    expect(history.getAll('sort')).toEqual(['queuedAt,DESC']);
    await user.click(within(within(bumped).getByRole('article', { name: 'Mesa 05 - Pedido #4098, Ronda 3' })).getByRole('button', { name: 'Repor' }));

    await expect.poll(() => sent.length).toBe(1);
    expect(sent[0]!.body).toEqual({ version: 3 });
    expect(screen.getByRole('button', { name: 'Retirados' })).toHaveAttribute('aria-pressed', 'true');
  });

  it.each([
    ['none taken off', page(), 'Nenhuma ronda retirada.'],
    ['refused', () => problem(500, 'INTERNAL'), 'Não foi possível carregar as rondas. Erro inesperado (INTERNAL).'],
    ['without a connection', () => HttpResponse.error(), 'Sem ligação — não foi possível carregar as rondas.'],
  ] as const)('"Retirados" says so when %s', async (_, history, text) => {
    kdsEndpoint((_, seen) => (new URL(seen.url).searchParams.get('status') === 'BUMPED' ? history() : page(TICKET.queued)()));
    const { user } = await atKitchen();
    await card(QUEUED);

    await user.click(screen.getByRole('button', { name: 'Retirados' }));

    expect(await within(await screen.findByRole('region', { name: 'Retirados' })).findByText(text)).toBeInTheDocument();
  });

  it('"Sem ligação" disables every command', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    kdsEndpoint(page(TICKET.ready));
    await atKitchen();
    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Sem ligação'));

    const ready = await card(READY);
    expect(within(ready).getByRole('button', { name: 'Retirar' })).toBeDisabled();
    expect(within(ready).getByRole('button', { name: 'Voltar à preparação' })).toBeDisabled();
  });

  it('a session that may only watch sees the rounds without commands', async () => {
    kdsEndpoint(page(TICKET.queued));
    await atKitchen({ permissions: [...SESSION.permissions, VIEWER] });

    expect(within(await card(QUEUED)).queryByRole('button')).not.toBeInTheDocument();
  });
});
