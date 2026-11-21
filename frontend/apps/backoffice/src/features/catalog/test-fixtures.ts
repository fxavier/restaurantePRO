import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { api, baixa } from '../../test/fixtures';

type Schemas = components['schemas'];
type Dto<Name extends string> = Schemas[`com.xavier.backend.catalog.model.dto.${Name}` & keyof Schemas];
export type ItemSummary = Dto<'ItemSummary'>;
export type FamilySummary = Dto<'FamilySummary'>;
export type UnitSummary = Dto<'UnitSummary'>;
export type TaxCategorySummary = Dto<'TaxCategorySummary'>;
export type ItemVariantSummary = Dto<'ItemVariantSummary'>;
export type BarcodeSummary = Dto<'BarcodeSummary'>;
export type PriceListSummary = Dto<'PriceListSummary'>;
export type ItemPriceSummary = Dto<'ItemPriceSummary'>;
export type ReleaseSummary = Dto<'ReleaseSummary'>;

/** Spring's page answer, as every catalog list sends it; `totalElements` above the content is a list cut at one page. */
export function page<T>(content: T[], size = 200, totalElements = content.length) {
  return { content, page: 0, size, totalElements };
}

export const carnes: FamilySummary = { id: 'f0000000-0000-4000-8000-000000000001', code: 'CARNES', name: 'Carnes', version: 0 };
export const bebidas: FamilySummary = { id: 'f0000000-0000-4000-8000-000000000002', code: 'BEBIDAS', name: 'Bebidas', version: 0 };
export const unidade: UnitSummary = { id: 'a0000000-0000-4000-8000-000000000001', code: 'UN', name: 'Unidade', version: 0 };
export const ivaNormal: TaxCategorySummary = {
  id: 'c0000000-0000-4000-8000-000000000001',
  code: 'IVA16',
  name: 'IVA normal',
  rates: [{ id: 'c1000000-0000-4000-8000-000000000001', ratePercent: '16.00', effectiveFrom: '2026-01-01T00:00:00Z' }],
  version: 0,
};

export function item(id: string, code: string, name: string, extra: Partial<ItemSummary> = {}): ItemSummary {
  return {
    id,
    code,
    name,
    familyId: carnes.id,
    unitId: unidade.id,
    taxCategoryId: ivaNormal.id,
    productionDestination: 'KITCHEN',
    kind: 'STANDARD',
    soldByWeight: false,
    availability: 'ACTIVE',
    version: 3,
    ...extra,
  };
}

export const bife = item('e0000000-0000-4000-8000-000000000001', 'C09', 'Bife da Vazia');
export const sangria = item('e0000000-0000-4000-8000-000000000002', 'B07', 'Sangria', {
  familyId: bebidas.id,
  productionDestination: 'BAR',
});

export const salaList: PriceListSummary = {
  id: 'b0000000-0000-4000-8000-000000000001',
  name: 'Sala',
  storeId: baixa.id,
  currency: 'MZN',
  version: 0,
};

export function release(id: string, status: string, extra: Partial<ReleaseSummary> = {}): ReleaseSummary {
  return { id, status, storeId: baixa.id, createdAt: '2026-11-19T10:30:00Z', version: 0, validationWarnings: [], ...extra };
}

/** The reference lists the item form needs: families, units and tax categories. */
export function catalogReference({
  families = [carnes, bebidas],
  units = [unidade],
  taxCategories = [ivaNormal],
}: { families?: FamilySummary[]; units?: UnitSummary[]; taxCategories?: TaxCategorySummary[] } = {}) {
  return [
    http.get(api('/api/v1/backoffice/catalog/families'), () => HttpResponse.json(page(families))),
    http.get(api('/api/v1/backoffice/catalog/units'), () => HttpResponse.json(page(units))),
    http.get(api('/api/v1/backoffice/catalog/tax-categories'), () => HttpResponse.json(page(taxCategories))),
  ];
}
