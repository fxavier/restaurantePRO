import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
type Dto<Name extends string> = Schemas[`com.xavier.backend.catalog.model.dto.${Name}` & keyof Schemas];

export type ItemSummary = Dto<'ItemSummary'>;
export type ItemCreateRequest = Dto<'ItemCreateRequest'>;
export type ItemUpdateRequest = Dto<'ItemUpdateRequest'>;
export type ItemVariantSummary = Dto<'ItemVariantSummary'>;
export type BarcodeSummary = Dto<'BarcodeSummary'>;
export type FamilySummary = Dto<'FamilySummary'>;
export type UnitSummary = Dto<'UnitSummary'>;
export type TaxCategorySummary = Dto<'TaxCategorySummary'>;
export type PriceListSummary = Dto<'PriceListSummary'>;
export type ItemPriceSummary = Dto<'ItemPriceSummary'>;
export type ReleaseSummary = Dto<'ReleaseSummary'>;
export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];
