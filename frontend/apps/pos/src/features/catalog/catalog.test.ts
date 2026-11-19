import { describe, expect, it } from 'vitest';
import { CATALOG, FAMILY, ITEM, VARIANT } from '../../test/order';
import { byCode, type CatalogSnapshot, itemLabel, needsChoices, search, toCatalog } from './catalog';

const catalog = toCatalog(CATALOG);

describe('catalog', () => {
  it('reads the snapshot: top-level families, no fee items, a subfamily item under its family', () => {
    expect(catalog.version).toBe(7);
    expect(catalog.families.map((family) => family.name)).toEqual(['Bebidas', 'Pratos', 'Menus']);
    expect(catalog.items.map((item) => item.code)).not.toContain('TAXA');
    expect(catalog.familyOf(catalog.item(ITEM.coffee.id)!)).toBe(FAMILY.drinks);
  });

  it('a missing answer is an error, not a guess', () => {
    expect(() => toCatalog(undefined)).toThrow('Incomplete catalog');
  });

  it('reads the nulls of a top-level family, an unpriced variant and an item barcode', () => {
    expect(catalog.families.map((family) => family.code)).toEqual(['BEBIDAS', 'PRATOS', 'MENUS']);
    expect(catalog.item(ITEM.water.id)?.variants[0]?.price).toBeNull();
    expect(byCode(catalog.items, '5601234000011')).toEqual({ item: catalog.item(ITEM.coffee.id) });
  });

  it('reads a version-1 snapshot, stored before item kinds and menus: every item STANDARD, without menu groups', () => {
    const v1Item = Object.fromEntries(
      Object.entries(ITEM.steak).filter(([key]) => key !== 'kind' && key !== 'menuGroups'),
    ) as CatalogSnapshot['items'][number];
    const read = toCatalog({ ...CATALOG, catalog: { ...CATALOG.catalog, schemaVersion: 1, items: [v1Item] } });
    expect(read.item(ITEM.steak.id)).toMatchObject({ kind: 'STANDARD', menuGroups: [] });
  });

  it('names an item with its variant', () => {
    expect(itemLabel(catalog, ITEM.water.id, VARIANT.small)).toBe('Água Pequena');
    expect(itemLabel(catalog, ITEM.water.id)).toBe('Água');
    expect(itemLabel(catalog, 'gone')).toBeUndefined();
  });

  it('an item with variants or menu groups asks first', () => {
    expect(needsChoices(catalog.item(ITEM.water.id)!)).toBe(true);
    expect(needsChoices(catalog.item(ITEM.menu.id)!)).toBe(true);
    expect(needsChoices(catalog.item(ITEM.coffee.id)!)).toBe(false);
  });

  it('searches names without accents or case, and codes by prefix', () => {
    expect(search(catalog.items, 'ÁGUA').map((item) => item.code)).toEqual(['AGUA']);
    expect(search(catalog.items, 'fran').map((item) => item.code)).toEqual(['FRANGO']);
    expect(search(catalog.items, 'bit').map((item) => item.code)).toEqual(['BITOQUE']);
    expect(search(catalog.items, '  ')).toHaveLength(catalog.items.length);
  });

  it('finds an exact code, or a barcode with the variant it names', () => {
    expect(byCode(catalog.items, 'bica')).toEqual({ item: catalog.item(ITEM.coffee.id) });
    expect(byCode(catalog.items, '5601234000011')).toEqual({ item: catalog.item(ITEM.coffee.id), variantId: undefined });
    expect(byCode(catalog.items, '5601234000028')).toEqual({ item: catalog.item(ITEM.water.id), variantId: VARIANT.large });
    expect(byCode(catalog.items, 'BIT')).toBeUndefined();
  });
});
