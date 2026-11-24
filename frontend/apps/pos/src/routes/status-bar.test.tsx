import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { productionAlerts, productionAlertsEndpoint } from '../test/alerts';
import { renderPos } from '../test/render-pos';
import { notificationsEndpoint, SESSION, token, tokenEndpoint } from '../test/server';

const VIEWER = `PERM_PRODUCTION_VIEW@${SESSION.storeId}`;

/** Ana logged in on `/tables`, with `permissions` instead of her default ones when given. */
async function loggedIn(permissions?: string[]) {
  tokenEndpoint(token('jwt-1'));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  if (permissions) act(() => rendered.services.session.start({ ...SESSION, permissions }));
  return rendered;
}

describe('status bar', () => {
  it('names the store, the terminal and the operator, as the light reference does', async () => {
    await loggedIn();

    const bar = await screen.findByRole('banner');
    expect(bar).toHaveTextContent('Loja Baixa');
    expect(within(bar).getByText('POS-02')).toBeInTheDocument();
    expect(bar).toHaveTextContent('Terminal:POS-02');
    expect(bar).toHaveTextContent('Ana Silva');
  });

  it('names the terminal by its name when the session has no code', async () => {
    const rendered = await loggedIn();

    act(() => rendered.services.session.start({ ...SESSION, terminalCode: null }));

    expect(await screen.findByRole('banner')).toHaveTextContent('Terminal:Caixa da sala');
  });

  it('shows the pending prints of the store to who may read the print queue', async () => {
    const requests = productionAlertsEndpoint(productionAlerts({ pendingPrintJobs: 1 }));
    await loggedIn([VIEWER]);

    const prints = await screen.findByRole('status', { name: 'Impressões' });
    await waitFor(() => expect(prints).toHaveTextContent('1 impressão pendente'));
    expect(requests[0]).toMatchObject({ posSession: SESSION.posSession });
  });

  it('says "impressões pendentes" for more than one, and nothing when none is pending', async () => {
    let pending = 3;
    productionAlertsEndpoint(() => productionAlerts({ pendingPrintJobs: pending })());
    const { queryClient } = await loggedIn([VIEWER]);

    const prints = await screen.findByRole('status', { name: 'Impressões' });
    await waitFor(() => expect(prints).toHaveTextContent('3 impressões pendentes'));
    pending = 0;
    await act(() => queryClient.refetchQueries({ queryKey: ['pos', 'production-alerts'] }));

    await waitFor(() => expect(prints).toBeEmptyDOMElement());
  });

  it('reads the pending prints again when the print queue changes', async () => {
    const { streams } = notificationsEndpoint('stream');
    let printed = false;
    productionAlertsEndpoint(() => productionAlerts({ pendingPrintJobs: printed ? 1 : 0 })());
    const { queryClient } = await loggedIn([VIEWER]);
    const prints = await screen.findByRole('status', { name: 'Impressões' });
    await waitFor(() => expect(streams).toHaveLength(1));
    // The stream is open, and the reads its opening asked for are done: what follows is the notice's alone.
    await screen.findByText('Ligado');
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    printed = true;
    act(() => streams.at(-1)!.change('PRINT_JOBS', '6f1c2a9e-0000-4000-8000-000000000888'));

    await waitFor(() => expect(prints).toHaveTextContent('1 impressão pendente'));
  });

  it('never reads the print queue without PRODUCTION_VIEW', async () => {
    const requests = productionAlertsEndpoint(productionAlerts({ pendingPrintJobs: 2 }));
    await loggedIn();

    expect(await screen.findByRole('banner')).toHaveTextContent('Ana Silva');
    expect(requests).toHaveLength(0);
    expect(screen.queryByText(/pendente/)).not.toBeInTheDocument();
  });
});
