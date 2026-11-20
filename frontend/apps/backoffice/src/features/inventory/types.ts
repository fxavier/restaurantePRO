import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
type Inventory = 'com.xavier.backend.inventory.';
type Dto<Name extends string> = Schemas[`${Inventory}${Name}` & keyof Schemas];
type Report<Row extends string> = Schemas[`${Inventory}model.dto.InventoryReportCom.xavier.backend.inventory.${Row}` &
  keyof Schemas];

export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];
export type WarehouseView = Dto<'model.dto.WarehouseView'>;
export type StockItemView = Dto<'model.dto.StockItemView'>;
export type StockRow = Dto<'model.dto.StockRow'>;
export type StatusView = Dto<'model.dto.StatusView'>;
export type ManualMovementRequest = Dto<'model.dto.ManualMovementRequest'>;
export type ValuationReport = Report<'model.dto.ValuationRow'>;
export type BelowMinimumReport = Report<'model.dto.BelowMinimumRow'>;
export type CountView = Dto<'count.model.dto.CountView'>;
export type CountRequest = Dto<'count.model.dto.CountRequest'>;
export type TransferView = Dto<'model.dto.TransferView'>;
export type SupplierView = Dto<'purchasing.model.dto.SupplierView'>;
export type SupplierRequest = Dto<'purchasing.model.dto.SupplierRequest'>;
export type PurchaseOrderView = Dto<'purchasing.model.dto.PurchaseOrderView'>;
export type PurchaseOrderRequest = Dto<'purchasing.model.dto.PurchaseOrderRequest'>;
export type ReceiptView = Dto<'purchasing.model.dto.ReceiptView'>;
export type ReceiptRequest = Dto<'purchasing.model.dto.ReceiptRequest'>;
export type RecipeView = Dto<'recipe.model.dto.RecipeView'>;
export type RecipeRequest = Dto<'recipe.model.dto.RecipeRequest'>;
export type RecipeVersionView = Dto<'recipe.model.dto.RecipeVersionView'>;
export type RecipeVersionRequest = Dto<'recipe.model.dto.RecipeVersionRequest'>;
