import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
export type OrderRow = Schemas['com.xavier.backend.orders.model.dto.OrderRow'];
export type OrderView = Schemas['com.xavier.backend.orders.model.dto.OrderView'];
export type LineView = Schemas['com.xavier.backend.orders.model.dto.LineView'];
export type BatchView = Schemas['com.xavier.backend.orders.model.dto.BatchView'];
export type AdjustmentView = Schemas['com.xavier.backend.orders.model.dto.LineView.AdjustmentView'];
export type OrderLifecycle = OrderRow['lifecycle'];
export type OrderMode = OrderRow['mode'];
