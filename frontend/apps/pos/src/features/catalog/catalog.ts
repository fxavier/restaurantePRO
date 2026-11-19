import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
type PosCatalogResponse = Schemas['com.xavier.backend.catalog.model.dto.PosCatalogResponse'];
/** The published catalog as the backend stores and sends it (`CatalogSnapshot`, schema version 2). */
export type CatalogSnapshot = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot'];
type WireItem = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Item'];

/** Prices are decimal strings, never parsed (FC §12). */
export type CatalogPrice = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Price'];
export type CatalogFamily = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Family'];
export type CatalogVariant = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Variant'];
export type CatalogBarcode = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Barcode'];
export type MenuOption = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.MenuOption'];
export type MenuGroup = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.MenuGroup'];
/** An item with its kind and menu groups, which a version-1 snapshot (published before R4) lacks. */
export type CatalogItem = Omit<WireItem, 'kind' | 'menuGroups'> & Required<Pick<WireItem, 'kind' | 'menuGroups'>>;

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

/** The POS gets the snapshot as stored: a version-1 one reads with every item STANDARD and no menu groups. */
const toItem = (item: WireItem): CatalogItem => ({ ...item, kind: item.kind ?? 'STANDARD', menuGroups: item.menuGroups ?? [] });

/** The contract marks what a snapshot always carries (FE-F28); only a missing answer is an error here. */
export function toCatalog(response: PosCatalogResponse | undefined): PosCatalog {
  if (!response) throw new Error('Incomplete catalog');
  const snapshot = response.catalog;
  const items = snapshot.items.map(toItem);
  const byId = new Map(items.map((item) => [item.id, item]));
  const parentOf = new Map(snapshot.families.map((family) => [family.id, family.parentId]));
  return {
    version: response.version,
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
