import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { INVENTORY, inventoryServer } from '../../test/inventory-fixtures';
import { renderApp } from '../../test/render-app';

/** The cell texts of a table's body rows, the first `columns` of each. */
function rowsOf(table: HTMLElement, columns: number) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) =>
      within(row)
        .getAllByRole('cell')
        .slice(0, columns)
        .map((cell) => cell.textContent.replace(/\s+/g, ' ')),
    );
}

describe('stock', () => {
  it("lists the first warehouse's stock with its minimum, average cost and value in the store's currency", async () => {
    inventoryServer();

    renderApp(INVENTORY);

    const table = await screen.findByRole('table', { name: 'Stock do armazém' });
    expect(rowsOf(table, 6)).toEqual([
      ['Polvo frescoSTK-POL-001', '3,5 kg', '5 kg', '9,80 MTn', '34,30 MTn', 'Abaixo do mínimo'],
      ['Azeite virgem extraSTK-AZT-004', '42 L', '10 L', '7,50 MTn', '315,00 MTn', 'Normal'],
    ]);
  });
});
