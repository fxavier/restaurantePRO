import type { components } from '@restaurantpro/api-client';

type PosCatalogResponse = components['schemas']['com.xavier.backend.catalog.model.dto.PosCatalogResponse'];

/**
 * The published catalog as the backend stores and sends it (`CatalogSnapshot`, schema version 2). The contract types
 * `catalog` as a Jackson `JsonNode` (gap-catalog-snapshot), so this is written from the backend record; only the
 * fields the POS reads are named. Prices are decimal strings, never parsed (FC §12).
 */
export type CatalogPrice = { amount: string; currency: string };
export type CatalogFamily = { id: string; code: string; name: string; parentId?: string | null };
export type CatalogVariant = { id: string; code: string; name: string; price?: CatalogPrice | null };
export type CatalogBarcode = { code: string; variantId?: string | null };
export type MenuOption = { itemId: string; variantId?: string | null; quantity: number | string };
export type MenuGroup = { code: string; name: string; minChoices: number; maxChoices: number; options: MenuOption[] };
export type CatalogItem = {
  id: string;
  code: string;
  name: string;
  familyId: string;
  price: CatalogPrice;
  variants: CatalogVariant[];
  barcodes: CatalogBarcode[];
  kind: 'STANDARD' | 'MENU' | 'FEE';
  menuGroups: MenuGroup[];
};
export type CatalogSnapshot = {
  schemaVersion: number;
  version: number;
  currency: string;
  families: CatalogFamily[];
  items: CatalogItem[];
};

/** The catalog the order screen reads: families for the rail, sellable items, and a lookup by id. */
export type PosCatalog = {
  version: number;
  /** Top-level families, in the snapshot's order (by code). */
  families: CatalogFamily[];
  /** Items a waiter can tap: every item but a delivery zone's fee. */
  items: CatalogItem[];
  item(id: string): CatalogItem | undefined;
  /** The top-level family an item belongs to (its own, or its subfamily's parent). */
  familyOf(item: CatalogItem): string;
};

/** An answer whose snapshot lacks its lists or an item its id, name or price cannot be shown: an error, not a guess. */
export function toCatalog(response: PosCatalogResponse | undefined): PosCatalog {
  const snapshot = response?.catalog as unknown as Partial<CatalogSnapshot> | undefined;
  if (!snapshot || !Array.isArray(snapshot.families) || !Array.isArray(snapshot.items)) {
    throw new Error('Incomplete catalog');
  }
  const items = snapshot.items.map((item) => {
    if (!item.id || !item.name || !item.code || !item.price) throw new Error('Incomplete catalog');
    return {
      ...item,
      variants: item.variants ?? [],
      barcodes: item.barcodes ?? [],
      kind: item.kind ?? 'STANDARD',
      menuGroups: item.menuGroups ?? [],
    };
  });
  const byId = new Map(items.map((item) => [item.id, item]));
  const parentOf = new Map(snapshot.families.map((family) => [family.id, family.parentId ?? undefined]));
  return {
    version: response?.version ?? snapshot.version ?? 0,
    families: snapshot.families.filter((family) => !family.parentId),
    items: items.filter((item) => item.kind !== 'FEE'),
    item: (id) => byId.get(id),
    familyOf: (item) => parentOf.get(item.familyId) ?? item.familyId,
  };
}

/** True when adding the item asks something first: its variant or its menu's choices. */
export function needsChoices(item: CatalogItem): boolean {
  return item.variants.length > 0 || item.menuGroups.length > 0;
}

/** "Água Pequena": the item's name, with its variant's when there is one. */
export function itemLabel(catalog: PosCatalog, itemId: string | undefined, variantId?: string | null): string | undefined {
  const item = itemId ? catalog.item(itemId) : undefined;
  if (!item) return undefined;
  const variant = variantId ? item.variants.find((candidate) => candidate.id === variantId) : undefined;
  return variant ? `${item.name} ${variant.name}` : item.name;
}

/** A plain, accent- and case-insensitive form, so "agua" finds "Água". */
export function normalize(text: string): string {
  return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().trim();
}

/** Items whose name contains the search, or whose code starts with it. */
export function search(items: CatalogItem[], text: string): CatalogItem[] {
  const wanted = normalize(text);
  if (!wanted) return items;
  return items.filter((item) => normalize(item.name).includes(wanted) || normalize(item.code).startsWith(wanted));
}

/** What an exact code or barcode names: an item, and the variant a barcode carries. */
export type CodeMatch = { item: CatalogItem; variantId?: string };

export function byCode(items: CatalogItem[], code: string): CodeMatch | undefined {
  const wanted = code.trim();
  if (!wanted) return undefined;
  for (const item of items) {
    if (item.code.toLowerCase() === wanted.toLowerCase()) return { item };
    const barcode = item.barcodes.find((candidate) => candidate.code === wanted);
    if (barcode) return { item, variantId: barcode.variantId ?? undefined };
  }
  return undefined;
}
