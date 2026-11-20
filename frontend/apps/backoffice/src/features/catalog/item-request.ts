import type { ItemValues } from './schemas';
import type { ItemCreateRequest, ItemSummary, ItemUpdateRequest } from './types';

/** The form a new item starts from: active, sold by the unit, without a destination. */
export const NEW_ITEM: ItemValues = {
  code: '',
  name: '',
  familyId: '',
  unitId: '',
  taxCategoryId: '',
  productionDestination: '',
  soldByWeight: false,
  availability: 'ACTIVE',
};

/** The form an existing item opens with. */
export function itemValues(item: ItemSummary): ItemValues {
  return {
    code: item.code ?? '',
    name: item.name ?? '',
    familyId: item.familyId ?? '',
    unitId: item.unitId ?? '',
    taxCategoryId: item.taxCategoryId ?? '',
    productionDestination: item.productionDestination ?? '',
    soldByWeight: item.soldByWeight ?? false,
    availability: item.availability === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
  };
}

/** A create request: no destination is sent as none, and the kind is the server's default (STANDARD). */
export function createItemRequest(values: ItemValues): ItemCreateRequest {
  const { code, name, familyId, unitId, taxCategoryId, soldByWeight, productionDestination } = values;
  return { code, name, familyId, unitId, taxCategoryId, soldByWeight, ...(productionDestination ? { productionDestination } : {}) };
}

/** An update request: the item's own kind goes back unchanged, under the version it was read at. */
export function updateItemRequest(values: ItemValues, item: ItemSummary): ItemUpdateRequest {
  return {
    ...createItemRequest(values),
    ...(item.kind ? { kind: item.kind } : {}),
    availability: values.availability,
    version: item.version ?? 0,
  };
}
