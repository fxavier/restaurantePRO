import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { productionAlerts, productionAlertsEndpoint, settlementAlerts, settlementAlertsEndpoint } from '../test/alerts';
import { renderPos } from '../test/render-pos';
import { notificationsEndpoint, SESSION, tableMap, tablesEndpoint, token, tokenEndpoint } from '../test/server';
import { MAP } from '../test/table-map';

const ORDER = '6f1c2a9e-0000-4000-8000-000000000777';
const perm = (name: string) => `PERM_${name}@${SESSION.storeId}`;

/** Ana on `/tables` over MAP, with `permissions` instead of her default ones when given. */
async function atTables(permissions?: string[]) {
  tokenEndpoint(token('jwt-1'));
  tablesEndpoint(tableMap(MAP));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  if (permissions) act(() => rendered.services.session.start({ ...SESSION, permissions }));
  await screen.findByRole('tab', { name: 'Sala' });
  return rendered;
}

const alerts = () => screen.findByRole('status', { name: 'Alertas' });

describe('/tables footer alerts', () => {
  it('links the payments in doubt to the payment screen of the oldest one’s order', async () => {
    const requests = settlementAlertsEndpoint(settlementAlerts({ paymentsToConfirm: 1, oldestOrderId: ORDER }));
    await atTables([perm('PAYMENT_COLLECT')]);

    const link = await within(await alerts()).findByRole('link', { name: '1 pagamento por confirmar' });
    expect(link).toHaveAttribute('href', `/orders/${ORDER}/payment`);
    expect(requests[0]).toMatchObject({ posSession: SESSION.posSession });
  });

  it('says "pagamentos" for more than one, and shows them unlinked when none has an order', async () => {
    settlementAlertsEndpoint(settlementAlerts({ paymentsToConfirm: 2, oldestOrderId: null }));
    await atTables([perm('PAYMENT_RESOLVE')]);

    const footer = await alerts();
    expect(await within(footer).findByText('2 pagamentos por confirmar')).toBeInTheDocument();
    expect(within(footer).queryByRole('link')).not.toBeInTheDocument();
  });

  it('links the late kitchen rounds to the kitchen display, with the delay the backend uses', async () => {
    productionAlertsEndpoint(productionAlerts({ delayedKitchenRounds: 2, kitchenDelayMinutes: 20 }));
    await atTables([perm('PRODUCTION_VIEW')]);

    const link = await within(await alerts()).findByRole('link', { name: 'Cozinha: 2 rondas > 20 min' });
    expect(link).toHaveAttribute('href', '/kds');
  });

  it('says "ronda" for one late round', async () => {
    productionAlertsEndpoint(productionAlerts({ delayedKitchenRounds: 1 }));
    await atTables([perm('PRODUCTION_VIEW')]);

    expect(await within(await alerts()).findByRole('link', { name: 'Cozinha: 1 ronda > 15 min' })).toBeInTheDocument();
  });

  it('reads no alert the session may not see', async () => {
    const payments = settlementAlertsEndpoint(settlementAlerts({ paymentsToConfirm: 3 }));
    const kitchen = productionAlertsEndpoint(productionAlerts({ delayedKitchenRounds: 3 }));
    await atTables();

    expect(within(await alerts()).queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText(/por confirmar|Cozinha:/)).not.toBeInTheDocument();
    expect(payments).toHaveLength(0);
    expect(kitchen).toHaveLength(0);
  });

  it('reads the payments in doubt again when an attempt changes', async () => {
    const { streams } = notificationsEndpoint('stream');
    let inDoubt = false;
    settlementAlertsEndpoint(() =>
      settlementAlerts(inDoubt ? { paymentsToConfirm: 1, oldestOrderId: ORDER } : { paymentsToConfirm: 0 })(),
    );
    const { queryClient } = await atTables([perm('PAYMENT_COLLECT')]);
    await waitFor(() => expect(streams).toHaveLength(1));
    // The stream is open, and the reads its opening asked for are done: what follows is the notice's alone.
    await screen.findByText('Ligado');
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    inDoubt = true;
    act(() => streams.at(-1)!.change('PAYMENT_ATTEMPT', '6f1c2a9e-0000-4000-8000-000000000999'));

    expect(await screen.findByRole('link', { name: '1 pagamento por confirmar' })).toBeInTheDocument();
  });
});

describe('/tables keyboard shortcuts', () => {
  it('"/" goes to the table search, and types "/" once there', async () => {
    const { user } = await atTables();

    await user.keyboard('/');

    const search = screen.getByRole('searchbox', { name: 'Procurar mesa' });
    expect(search).toHaveFocus();
    expect(search).toHaveValue('');
    await user.keyboard('/');
    expect(search).toHaveValue('/');
  });

  it('F3 opens the new takeaway dialog', async () => {
    const { user } = await atTables();

    await user.keyboard('{F3}');

    expect(await screen.findByRole('dialog', { name: 'Novo pedido fora de sala' })).toBeInTheDocument();
  });

  it('F2 opens the new reservation dialog for who may book', async () => {
    const { user } = await atTables([...SESSION.permissions, perm('RESERVATION_MANAGE')]);

    await user.keyboard('{F2}');

    expect(await screen.findByRole('dialog', { name: 'Nova reserva' })).toBeInTheDocument();
  });

  it('F2 does nothing, and is not offered, without RESERVATION_MANAGE', async () => {
    const { user } = await atTables();

    await user.keyboard('{F2}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const hints = screen.getByRole('note', { name: 'Atalhos de teclado' });
    expect(hints).toHaveTextContent('F3 takeaway · / procurar mesa');
    expect(hints).not.toHaveTextContent('F2');
  });

  it('lists every shortcut the session may use', async () => {
    await atTables([...SESSION.permissions, perm('RESERVATION_MANAGE')]);

    expect(screen.getByRole('note', { name: 'Atalhos de teclado' })).toHaveTextContent(
      'F2 reserva · F3 takeaway · / procurar mesa',
    );
  });

  it('leaves a key typed in a field alone', async () => {
    const { user } = await atTables([...SESSION.permissions, perm('RESERVATION_MANAGE')]);
    await user.click(screen.getByRole('searchbox', { name: 'Procurar mesa' }));

    await user.keyboard('{F2}{F3}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
