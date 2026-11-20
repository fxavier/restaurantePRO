import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { baixa, signedIn } from '../../test/fixtures';
import { productionHandlers, type ProductionState } from '../../test/production-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const PRODUCTION = `/production?storeId=${baixa.id}`;

function productionServer(initial: Partial<ProductionState> = {}, session?: Parameters<typeof signedIn>[0]) {
  const { state, handlers } = productionHandlers(initial);
  server.use(...signedIn(session));
  server.use(...handlers);
  return state;
}

describe('printers', () => {
  it("lists the selected store's printers with their address", async () => {
    productionServer();

    renderApp(PRODUCTION);

    const list = await screen.findByRole('list', { name: 'Impressoras da loja' });
    const kitchen = within(list).getByRole('listitem', { name: 'Cozinha Quentes' });
    expect(within(kitchen).getByText('192.168.1.181:9100')).toBeInTheDocument();
    expect(within(list).getByRole('listitem', { name: 'Bar Central' })).toBeInTheDocument();
  });
});
