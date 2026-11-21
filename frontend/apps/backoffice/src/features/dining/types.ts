import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
export type RoomAreaSummary = Schemas['com.xavier.backend.dining.model.dto.RoomAreaSummary'];
export type RoomAreaCreateRequest = Schemas['com.xavier.backend.dining.model.dto.RoomAreaCreateRequest'];
export type RoomAreaUpdateRequest = Schemas['com.xavier.backend.dining.model.dto.RoomAreaUpdateRequest'];
export type RestaurantTableSummary = Schemas['com.xavier.backend.dining.model.dto.RestaurantTableSummary'];
export type RestaurantTableCreateRequest = Schemas['com.xavier.backend.dining.model.dto.RestaurantTableCreateRequest'];
export type RestaurantTableUpdateRequest = Schemas['com.xavier.backend.dining.model.dto.RestaurantTableUpdateRequest'];
export type DiningSettingsView = Schemas['com.xavier.backend.dining.model.dto.DiningSettingsView'];
export type DiningSettingsRequest = Schemas['com.xavier.backend.dining.model.dto.DiningSettingsRequest'];
export type ReservationView = Schemas['com.xavier.backend.dining.model.dto.ReservationView'];
