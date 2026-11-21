import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
export type ZoneView = Schemas['com.xavier.backend.orders.delivery.model.dto.ZoneView'];
export type ZoneCreateRequest = Schemas['com.xavier.backend.orders.delivery.model.dto.ZoneCreateRequest'];
export type ZoneUpdateRequest = Schemas['com.xavier.backend.orders.delivery.model.dto.ZoneUpdateRequest'];
export type DeliveryView = Schemas['com.xavier.backend.orders.delivery.model.dto.DeliveryView'];
export type DeliveryStatus = NonNullable<DeliveryView['status']>;
export type ItemSummary = Schemas['com.xavier.backend.catalog.model.dto.ItemSummary'];
export type ItemVariantSummary = Schemas['com.xavier.backend.catalog.model.dto.ItemVariantSummary'];
export type EmployeeSummary = Schemas['com.xavier.backend.identity.model.dto.EmployeeSummary'];
export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];
