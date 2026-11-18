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

  it('an answer without its snapshot lists is an error, not a guess', () => {
    expect(() => toCatalog({ ...CATALOG, catalog: undefined })).toThrow('Incomplete catalog');
    expect(() => toCatalog(undefined)).toThrow('Incomplete catalog');
  });

  it('an item without its family, or a menu option without its item, is an error, not a guess', () => {
    type Item = NonNullable<CatalogSnapshot['items']>[number];
    const without = (item: Item) => ({ ...CATALOG, catalog: { ...CATALOG.catalog, items: [item] } });
    expect(() => toCatalog(without({ ...ITEM.steak, familyId: undefined }))).toThrow('Incomplete catalog');
    const menu = ITEM.menu;
    const optionWithoutItem = { ...menu.menuGroups[0], options: [{ quantity: 1 }] };
    expect(() => toCatalog(without({ ...menu, menuGroups: [optionWithoutItem] }))).toThrow('Incomplete catalog');
  });

  it('reads the nulls the backend writes for a top-level family, an unpriced variant and an item barcode', () => {
    // Jackson writes an absent parent, price or variant as null; the contract marks no field nullable.
    const wire = JSON.parse(
      JSON.stringify({
        ...CATALOG.catalog,
        families: CATALOG.catalog?.families?.map((family) => ({ parentId: null, ...family })),
        items: [
          { ...ITEM.water, variants: ITEM.water.variants.map((variant) => ({ price: null, ...variant })) },
          { ...ITEM.coffee, barcodes: [{ code: '5601234000011', variantId: null }] },
        ],
      }),
    );
    const read = toCatalog({ ...CATALOG, catalog: wire });
    expect(read.families.map((family) => family.code)).toEqual(['BEBIDAS', 'PRATOS', 'MENUS']);
    expect(read.item(ITEM.water.id)?.variants[0]?.price).toBeNull();
    expect(byCode(read.items, '5601234000011')).toEqual({ item: read.item(ITEM.coffee.id) });
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
