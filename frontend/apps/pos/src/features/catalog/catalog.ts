import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
type PosCatalogResponse = Schemas['com.xavier.backend.catalog.model.dto.PosCatalogResponse'];
/** The published catalog as the backend stores and sends it (`CatalogSnapshot`, schema version 2). */
export type CatalogSnapshot = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot'];
type WirePrice = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Price'];
type WireFamily = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Family'];
type WireVariant = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Variant'];
type WireBarcode = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Barcode'];
type WireMenuOption = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.MenuOption'];
type WireMenuGroup = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.MenuGroup'];
type WireItem = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Item'];

/**
 * A snapshot record as the POS reads it: the `Needed` fields present (the contract marks no field required), and the
 * `Nullable` ones possibly null, as Jackson writes an absent parent, price or variant (the contract marks no field
 * nullable either).
 */
type Read<T, Needed extends keyof T, Nullable extends keyof T = never> = Omit<T, Needed | Nullable> & {
  [K in Needed]-?: NonNullable<T[K]>;
} & { [K in Nullable]?: T[K] | null };

/** Prices are decimal strings, never parsed (FC §12). */
export type CatalogPrice = Read<WirePrice, 'amount' | 'currency'>;
export type CatalogFamily = Read<WireFamily, 'id' | 'code' | 'name', 'parentId'>;
export type CatalogVariant = Read<Omit<WireVariant, 'price'>, 'id' | 'code' | 'name'> & { price?: CatalogPrice | null };
export type CatalogBarcode = Read<WireBarcode, 'code', 'variantId'>;
export type MenuOption = Read<WireMenuOption, 'itemId' | 'quantity', 'variantId'>;
export type MenuGroup = Read<Omit<WireMenuGroup, 'options'>, 'code' | 'name' | 'minChoices' | 'maxChoices'> & {
  options: MenuOption[];
};
export type CatalogItem = Read<
  Omit<WireItem, 'price' | 'variants' | 'barcodes' | 'menuGroups'>,
  'id' | 'code' | 'name' | 'familyId' | 'kind'
> & { price: CatalogPrice; variants: CatalogVariant[]; barcodes: CatalogBarcode[]; menuGroups: MenuGroup[] };

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

const INCOMPLETE = 'Incomplete catalog';

function need<T>(value: T | null | undefined): T {
  if (value === undefined || value === null) throw new Error(INCOMPLETE);
  return value;
}

const toPrice = (price: WirePrice | null | undefined): CatalogPrice => ({
  ...need(price),
  amount: need(price?.amount),
  currency: need(price?.currency),
});

const toFamily = (family: WireFamily): CatalogFamily => ({
  ...family,
  id: need(family.id),
  code: need(family.code),
  name: need(family.name),
});

const toVariant = (variant: WireVariant): CatalogVariant => ({
  ...variant,
  id: need(variant.id),
  code: need(variant.code),
  name: need(variant.name),
  price: variant.price == null ? variant.price : toPrice(variant.price),
});

const toBarcode = (barcode: WireBarcode): CatalogBarcode => ({ ...barcode, code: need(barcode.code) });

const toOption = (option: WireMenuOption): MenuOption => ({
  ...option,
  itemId: need(option.itemId),
  quantity: need(option.quantity),
});

const toGroup = (group: WireMenuGroup): MenuGroup => ({
  ...group,
  code: need(group.code),
  name: need(group.name),
  minChoices: need(group.minChoices),
  maxChoices: need(group.maxChoices),
  options: (group.options ?? []).map(toOption),
});

function toItem(item: WireItem): CatalogItem {
  if (!item.id || !item.name || !item.code || !item.price) throw new Error(INCOMPLETE);
  return {
    ...item,
    id: item.id,
    code: item.code,
    name: item.name,
    familyId: need(item.familyId),
    kind: item.kind ?? 'STANDARD',
    price: toPrice(item.price),
    variants: (item.variants ?? []).map(toVariant),
    barcodes: (item.barcodes ?? []).map(toBarcode),
    menuGroups: (item.menuGroups ?? []).map(toGroup),
  };
}

/**
 * An answer whose snapshot lacks its lists, or a record a field the POS shows or sends (an item's id, name, price or
 * family, a menu option's item…): an error, not a guess.
 */
export function toCatalog(response: PosCatalogResponse | undefined): PosCatalog {
  const snapshot = response?.catalog;
  if (!snapshot || !Array.isArray(snapshot.families) || !Array.isArray(snapshot.items)) {
    throw new Error(INCOMPLETE);
  }
  const families = snapshot.families.map(toFamily);
  const items = snapshot.items.map(toItem);
  const byId = new Map(items.map((item) => [item.id, item]));
  const parentOf = new Map(families.map((family) => [family.id, family.parentId ?? undefined]));
  return {
    version: response?.version ?? snapshot.version ?? 0,
    families: families.filter((family) => !family.parentId),
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
