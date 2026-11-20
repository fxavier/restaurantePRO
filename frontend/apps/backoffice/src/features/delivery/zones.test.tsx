import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { centro, feeFar, feeNear, polana, zonesServer } from '../../test/delivery-fixtures';
import { baixa } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';

const ZONES = `/delivery/zones?storeId=${baixa.id}`;

describe('delivery zones list', () => {
  it("lists the selected store's zones with their code, state and fee item", async () => {
    const state = zonesServer();

    renderApp(ZONES);

    const list = await screen.findByRole('list', { name: 'Zonas da loja' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]!).getByRole('heading', { name: `${centro.code} ${centro.name}` })).toBeInTheDocument();
    expect(within(items[0]!).getByText('Ativa')).toBeInTheDocument();
    expect(await within(items[0]!).findByText(feeNear.name!)).toBeInTheDocument();
    expect(within(items[1]!).getByRole('heading', { name: `${polana.code} ${polana.name}` })).toBeInTheDocument();
    expect(within(items[1]!).getByText('Inativa')).toBeInTheDocument();
    expect(within(items[1]!).getByText(feeFar.name!)).toBeInTheDocument();
    expect(state.lists).toEqual([`?storeId=${baixa.id}`]);
  });
});
