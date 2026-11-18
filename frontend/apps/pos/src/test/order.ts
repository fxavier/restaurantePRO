import type { components } from '@restaurantpro/api-client';
import { HttpResponse } from 'msw';
import type { CatalogSnapshot } from '../features/catalog/catalog';
import { queue, recording, SESSION } from './server';
import { id } from './table-map';

type Schemas = components['schemas'];
export type OrderView = Schemas['com.xavier.backend.orders.model.dto.OrderView'];
export type LineView = Schemas['com.xavier.backend.orders.model.dto.LineView'];
type PosCatalogResponse = Schemas['com.xavier.backend.catalog.model.dto.PosCatalogResponse'];
type CatalogItem = Schemas['com.xavier.backend.catalog.model.dto.CatalogSnapshot.Item'];
type OrderSummary = Schemas['com.xavier.backend.orders.model.dto.OrderSummary'];
export type OpenTableRequest = Schemas['com.xavier.backend.orders.model.dto.OpenTableRequest'];
export type AddLineRequest = Schemas['com.xavier.backend.orders.model.dto.AddLineRequest'];
export type UpdateLineRequest = Schemas['com.xavier.backend.orders.model.dto.UpdateLineRequest'];
export type DeleteLineRequest = Schemas['com.xavier.backend.orders.model.dto.DeleteLineRequest'];
export type ConfirmBatchRequest = Schemas['com.xavier.backend.orders.model.dto.ConfirmBatchRequest'];
export type BatchView = Schemas['com.xavier.backend.orders.model.dto.BatchView'];

const mzn = (amount: string) => ({ amount, currency: 'MZN' });

/** Families: Bebidas (with the subfamily Cafés), Pratos, Menus. */
export const FAMILY = { drinks: id(601), coffees: id(602), dishes: id(603), menus: id(604) };

const item = (n: number, code: string, name: string, familyId: string, price: string, fields: Partial<CatalogItem> = {}) =>
  ({
    id: id(n),
    code,
    name,
    familyId,
    price: mzn(price),
    variants: [],
    barcodes: [],
    kind: 'STANDARD',
    menuGroups: [],
    ...fields,
  }) satisfies CatalogItem;

export const VARIANT = { small: id(721), large: id(722) };

export const ITEM = {
  coffee: item(701, 'BICA', 'Bica', FAMILY.coffees, '50.00', { barcodes: [{ code: '5601234000011' }] }),
  water: item(702, 'AGUA', 'Água', FAMILY.drinks, '60.00', {
    variants: [
      { id: VARIANT.small, code: 'AGUA-P', name: 'Pequena' },
      { id: VARIANT.large, code: 'AGUA-G', name: 'Grande', price: mzn('90.00') },
    ],
    barcodes: [{ code: '5601234000028', variantId: VARIANT.large }],
  }),
  juice: item(703, 'SUMO', 'Sumo de laranja', FAMILY.drinks, '120.00'),
  steak: item(704, 'BITOQUE', 'Bitoque', FAMILY.dishes, '450.00'),
  chicken: item(705, 'FRANGO', 'Frango grelhado', FAMILY.dishes, '400.00'),
  menu: item(706, 'MENU', 'Menu do dia', FAMILY.menus, '600.00', {
    kind: 'MENU',
    menuGroups: [
      {
        code: 'PRATO',
        name: 'Prato',
        minChoices: 1,
        maxChoices: 1,
        options: [
          { itemId: id(704), quantity: 1 },
          { itemId: id(705), quantity: 1 },
        ],
      },
      {
        code: 'BEBIDA',
        name: 'Bebidas',
        minChoices: 1,
        maxChoices: 2,
        options: [
          { itemId: id(702), variantId: VARIANT.small, quantity: 1 },
          { itemId: id(701), quantity: 1 },
          { itemId: id(703), quantity: 1 },
        ],
      },
    ],
  }),
  fee: item(707, 'TAXA', 'Taxa de entrega', FAMILY.dishes, '100.00', { kind: 'FEE' }),
};

export const SNAPSHOT: CatalogSnapshot = {
  schemaVersion: 2,
  version: 7,
  currency: 'MZN',
  families: [
    { id: FAMILY.drinks, code: 'BEBIDAS', name: 'Bebidas' },
    { id: FAMILY.coffees, code: 'CAFES', name: 'Cafés', parentId: FAMILY.drinks },
    { id: FAMILY.dishes, code: 'PRATOS', name: 'Pratos' },
    { id: FAMILY.menus, code: 'MENUS', name: 'Menus' },
  ],
  items: Object.values(ITEM),
};

export const CATALOG: PosCatalogResponse = {
  releaseId: id(600),
  storeId: SESSION.storeId,
  version: 7,
  effectiveAt: '2026-11-18T06:00:00Z',
  catalog: SNAPSHOT,
};

export const ORDER_ID = id(500);
export const BATCH_ID = id(550);

const draft = (n: number, fields: Partial<LineView>): LineView => ({
  id: id(n),
  status: 'DRAFT',
  quantity: 1,
  version: 0,
  preview: true,
  kind: 'STANDARD',
  automatic: false,
  components: [],
  adjustments: [],
  ...fields,
});

export const LINE = {
  water: draft(801, {
    itemId: ITEM.water.id,
    variantId: VARIANT.small,
    quantity: 2,
    notes: 'Sem gelo',
    version: 1,
    unitPrice: { amount: 60, currency: 'MZN' },
    gross: { amount: 120, currency: 'MZN' },
  }),
  coffee: draft(802, {
    itemId: ITEM.coffee.id,
    quantity: 1,
    version: 3,
    unitPrice: { amount: 50, currency: 'MZN' },
    gross: { amount: 50, currency: 'MZN' },
  }),
  steak: {
    id: id(803),
    status: 'CONFIRMED',
    itemId: ITEM.steak.id,
    itemCode: 'BITOQUE',
    description: 'Bitoque',
    quantity: 3,
    netQuantity: 3,
    version: 1,
    batchId: BATCH_ID,
    unitPrice: { amount: 450, currency: 'MZN' },
    gross: { amount: 1350, currency: 'MZN' },
    preview: false,
    kind: 'STANDARD',
    automatic: false,
    components: [],
    adjustments: [],
  } satisfies LineView,
};

/** M2's open order: two drafts (one with a note) and a first round of three Bitoques, already sent. */
export const ORDER: OrderView = {
  id: ORDER_ID,
  storeId: SESSION.storeId,
  terminalId: id(1),
  orderNumber: 12,
  mode: 'DINE_IN',
  tableId: id(2),
  tableCode: 'M2',
  guests: 3,
  lifecycle: 'OPEN',
  billingLock: 'NONE',
  businessDay: '2026-11-18',
  openedAt: '2026-11-18T10:05:00Z',
  openedByEmployeeId: id(77),
  version: 4,
  catalogVersion: 7,
  lines: [LINE.water, LINE.coffee, LINE.steak],
  batches: [
    {
      id: BATCH_ID,
      orderId: ORDER_ID,
      batchNo: 1,
      status: 'DISPATCHED',
      confirmedAt: '2026-11-18T10:10:00Z',
      orderVersion: 3,
      lines: [LINE.steak],
    },
  ],
  due: { amount: 1350, currency: 'MZN' },
};

export const NEW_ORDER_ID = id(501);

/** M1 just opened for two: nothing on it yet. */
export const NEW_ORDER: OrderView = {
  ...ORDER,
  id: NEW_ORDER_ID,
  tableId: id(1),
  tableCode: 'M1',
  guests: 2,
  orderNumber: 13,
  version: 0,
  catalogVersion: undefined,
  lines: [],
  batches: [],
  due: { amount: 0, currency: 'MZN' },
};

export const withLines = (order: OrderView, lines: LineView[], fields: Partial<OrderView> = {}): OrderView => ({
  ...order,
  lines,
  ...fields,
});

export const summaryOf = (order: OrderView): OrderSummary => ({
  id: order.id,
  tableId: order.tableId,
  tableCode: order.tableCode,
  guests: order.guests,
  lifecycle: order.lifecycle,
  mode: order.mode,
  orderNumber: order.orderNumber,
  openedAt: order.openedAt,
  version: order.version,
});

type Answer<Body = undefined> = (body: Body) => Response | Promise<Response>;

/** `GET /api/v1/pos/catalog`. */
export const catalogEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/catalog', queue(...(answers.length ? answers : [() => HttpResponse.json(CATALOG)])));

/** `GET /api/v1/pos/orders/{id}`. */
export const orderEndpoint = (orderId: string, ...answers: Answer[]) =>
  recording<undefined>('get', `/api/v1/pos/orders/${orderId}`, queue(...answers));

/** `POST /api/v1/pos/tables/{tableId}/open`. */
export const openTableEndpoint = (tableId: string, ...answers: Answer<OpenTableRequest>[]) =>
  recording<OpenTableRequest>('post', `/api/v1/pos/tables/${tableId}/open`, queue(...answers));

/** `POST /api/v1/pos/orders/{id}/lines`. */
export const addLineEndpoint = (orderId: string, ...answers: Answer<AddLineRequest>[]) =>
  recording<AddLineRequest>('post', `/api/v1/pos/orders/${orderId}/lines`, queue(...answers));

/** `PUT /api/v1/pos/orders/{id}/lines/{lineId}`. */
export const updateLineEndpoint = (orderId: string, lineId: string, ...answers: Answer<UpdateLineRequest>[]) =>
  recording<UpdateLineRequest>('put', `/api/v1/pos/orders/${orderId}/lines/${lineId}`, queue(...answers));

/** `DELETE /api/v1/pos/orders/{id}/lines/{lineId}` (204). */
export const deleteLineEndpoint = (orderId: string, lineId: string, ...answers: Answer<DeleteLineRequest>[]) =>
  recording<DeleteLineRequest>('delete', `/api/v1/pos/orders/${orderId}/lines/${lineId}`, queue(...answers));

/** `POST /api/v1/pos/orders/{id}/batches` ("Pedir"). */
export const batchEndpoint = (orderId: string, ...answers: Answer<ConfirmBatchRequest>[]) =>
  recording<ConfirmBatchRequest>('post', `/api/v1/pos/orders/${orderId}/batches`, queue(...answers));

export const json = (body: object) => () => HttpResponse.json(body);
export const created = (body: object) => () => HttpResponse.json(body, { status: 201 });
export const noContent = () => () => new HttpResponse(null, { status: 204 });
