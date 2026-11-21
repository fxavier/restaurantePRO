import { within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type {
  PurchaseOrderView,
  ReceiptView,
  RecipeView,
  StockItemView,
  StockRow,
  SupplierView,
  TransferView,
  WarehouseView,
} from '../features/inventory/types';
import { admin, api, baixa, signedIn, type SessionView } from './fixtures';
import { server } from './server';

/** The inventory area of Loja Baixa as the backend answers it (MZN, Africa/Maputo), for the inventory tests. */
export const INVENTORY = `/inventory?storeId=${baixa.id}`;

/** A fixture whose identifying fields are known, so a test can use them without a non-null assertion. */
type Known<T, K extends keyof T> = T & { [P in K]-?: NonNullable<T[P]> };

/** A manager holding every inventory permission (backend `access.Permission`). */
export const inventoryManager: SessionView = {
  ...admin,
  permissions: [
    ...admin.permissions,
    'PERM_INVENTORY_MANAGE',
    'PERM_INVENTORY_MOVE',
    'PERM_INVENTORY_TRANSFER',
    'PERM_INVENTORY_COUNT',
    'PERM_INVENTORY_COUNT_APPROVE',
    'PERM_RECIPE_MANAGE',
    'PERM_PURCHASE_MANAGE',
    'PERM_GOODS_RECEIVE',
  ],
};

export const cozinha: Known<WarehouseView, 'id' | 'name'> = {
  id: 'c0000000-0000-4000-8000-000000000001',
  storeId: baixa.id,
  code: 'COZ',
  name: 'Cozinha',
  active: true,
  negativePolicy: 'ALLOW',
  version: 0,
};
export const bar: Known<WarehouseView, 'id' | 'name'> = { ...cozinha, id: 'c0000000-0000-4000-8000-000000000002', code: 'BAR', name: 'Bar' };

function item(n: number, code: string, name: string, baseUnit: string): Known<StockItemView, 'id' | 'name' | 'code'> {
  return { id: `d0000000-0000-4000-8000-00000000000${n}`, code, name, baseUnit, kind: 'PURCHASED', active: true, version: 0 };
}
export const polvo = item(1, 'STK-POL-001', 'Polvo fresco', 'kg');
export const azeite = item(2, 'STK-AZT-004', 'Azeite virgem extra', 'L');
export const batata = item(3, 'STK-BAT-012', 'Batata', 'kg');

export function stockRow(of: StockItemView, quantity: string, minQuantity: string | undefined, averageCost: string,
  value: string, warehouse = cozinha): StockRow {
  return {
    stockItemId: of.id,
    warehouseId: warehouse.id,
    code: of.code,
    name: of.name,
    baseUnit: of.baseUnit,
    quantity,
    ...(minQuantity === undefined ? {} : { minQuantity }),
    belowMinimum: minQuantity !== undefined && Number(quantity) < Number(minQuantity),
    averageCost,
    value,
  };
}

export const lusitania: Known<SupplierView, 'id' | 'name'> = {
  id: 'e0000000-0000-4000-8000-000000000001',
  code: 'SUP-01',
  name: 'Lusitânia Pescados',
  taxId: '400123456',
  phone: '+258 84 000 0000',
  email: 'encomendas@lusitania.co.mz',
  active: true,
  version: 0,
};

export const orderSubmitted: PurchaseOrderView = {
  id: 'f0000000-0000-4000-8000-000000000001',
  storeId: baixa.id,
  warehouseId: cozinha.id,
  supplierId: lusitania.id,
  number: 'OC-904',
  status: 'SUBMITTED',
  progress: 'PARTIAL',
  expectedAt: '2026-11-21',
  createdAt: '2026-11-18T09:00:00Z',
  lines: [{ id: 'f1000000-0000-4000-8000-000000000001', stockItemId: polvo.id, quantity: '100', ordered: '100', received: '80', outstanding: '20', unitCost: '9.80', progress: 'PARTIAL' }],
  version: 2,
};
export const orderDraft: PurchaseOrderView = {
  ...orderSubmitted,
  id: 'f0000000-0000-4000-8000-000000000002',
  number: 'OC-905',
  status: 'DRAFT',
  progress: 'NONE',
  version: 0,
};

export const receiptDraft: ReceiptView = {
  id: 'f2000000-0000-4000-8000-000000000001',
  storeId: baixa.id,
  warehouseId: cozinha.id,
  supplierId: lusitania.id,
  purchaseOrderId: orderSubmitted.id,
  supplierDocumentRef: 'GR-882',
  status: 'DRAFT',
  receivedAt: '2026-11-20T07:15:00Z',
  createdAt: '2026-11-20T07:15:00Z',
  lines: [{ id: 'f2100000-0000-4000-8000-000000000001', stockItemId: polvo.id, packQuantity: '8', quantityBase: '80', unitCostBase: '9.80' }],
  warnings: [],
  version: 0,
};

export const polvoLagareiro: RecipeView = {
  id: 'a1000000-0000-4000-8000-000000000001',
  name: 'Polvo à lagareiro',
  itemId: 'a2000000-0000-4000-8000-000000000001',
  version: 3,
  versions: [
    { id: 'a1100000-0000-4000-8000-000000000001', recipeId: 'a1000000-0000-4000-8000-000000000001', versionNo: 1,
      status: 'SUPERSEDED', yieldQuantity: '1', yieldUnit: 'un', ingredients: [], version: 1 },
    {
      id: 'a1100000-0000-4000-8000-000000000002',
      recipeId: 'a1000000-0000-4000-8000-000000000001',
      versionNo: 2,
      status: 'ACTIVE',
      yieldQuantity: '1',
      yieldUnit: 'un',
      effectiveFrom: '2026-11-01T00:00:00Z',
      ingredients: [
        { stockItemId: polvo.id, quantity: '0.350', unit: 'kg', kind: 'INGREDIENT', modes: [] },
        { stockItemId: azeite.id, quantity: '0.060', unit: 'L', kind: 'INGREDIENT', modes: [] },
      ],
      version: 1,
    },
    { id: 'a1100000-0000-4000-8000-000000000003', recipeId: 'a1000000-0000-4000-8000-000000000001', versionNo: 3,
      status: 'DRAFT', yieldQuantity: '1', yieldUnit: 'un',
      ingredients: [{ stockItemId: polvo.id, quantity: '0.400', unit: 'kg', kind: 'INGREDIENT', modes: [] }], version: 0 },
  ],
};

export const transferDraft: TransferView = {
  id: 'b1000000-0000-4000-8000-000000000001',
  fromStoreId: baixa.id,
  toStoreId: baixa.id,
  fromWarehouseId: cozinha.id,
  toWarehouseId: bar.id,
  status: 'DRAFT',
  createdAt: '2026-11-20T08:00:00Z',
  lines: [{ stockItemId: azeite.id, quantityShipped: '5', unitCost: '7.50' }],
  version: 0,
};

/** What the inventory server holds; a test changes it to set the scene. */
export type InventoryState = {
  warehouses: WarehouseView[];
  items: StockItemView[];
  stock: Record<string, StockRow[]>;
  suppliers: SupplierView[];
  orders: PurchaseOrderView[];
  receipts: ReceiptView[];
  recipes: RecipeView[];
  transfers: TransferView[];
  /** Every request the area made, in order. */
  requests: Seen[];
};

export type Seen = { method: string; path: string; search: string; body: unknown; idempotencyKey: string | null };

async function seen(request: Request): Promise<Seen> {
  const url = new URL(request.url);
  const text = request.method === 'GET' ? '' : await request.clone().text();
  return {
    method: request.method,
    path: url.pathname,
    search: url.search,
    body: text ? JSON.parse(text) : undefined,
    idempotencyKey: request.headers.get('Idempotency-Key'),
  };
}

const page = <T>(content: T[]) => ({ content, page: 0, size: content.length, totalElements: content.length });
const BASE = '/api/v1/backoffice/inventory';

/** The signed-in manager and the area's reads, answered from `state`; commands are added by each test. */
export function inventoryServer({ session = inventoryManager, ...initial }: Partial<InventoryState> & { session?: SessionView } = {}) {
  const state: InventoryState = {
    warehouses: [cozinha, bar],
    items: [polvo, azeite, batata],
    stock: {
      [cozinha.id]: [stockRow(polvo, '3.500', '5.000', '9.80', '34.30'), stockRow(azeite, '42.000', '10.000', '7.50', '315.00')],
      [bar.id]: [],
    },
    suppliers: [lusitania],
    orders: [orderSubmitted, orderDraft],
    receipts: [receiptDraft],
    recipes: [polvoLagareiro],
    transfers: [transferDraft],
    requests: [],
    ...initial,
  };
  server.events.removeAllListeners('request:start');
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname.startsWith(BASE)) void seen(request).then((s) => state.requests.push(s));
  });
  server.use(...signedIn({ session }));
  server.use(
    http.get(api(`${BASE}/warehouses`), () => HttpResponse.json(state.warehouses)),
    http.get(api(`${BASE}/stock-items`), () => HttpResponse.json(page(state.items))),
    http.get(api(`${BASE}/stock`), ({ request }) => {
      const query = new URL(request.url).searchParams;
      const rows = state.stock[query.get('warehouseId') ?? ''] ?? [];
      return HttpResponse.json(page(query.get('belowMinimum') === 'true' ? rows.filter((row) => row.belowMinimum) : rows));
    }),
    http.get(api(`${BASE}/status`), () =>
      HttpResponse.json({ storeId: baixa.id, state: 'ACTIVE', activeFrom: '2026-11-01T06:00:00Z', openExceptions: 0,
        negativeBalances: 0, pendingFacts: 0, pendingVoids: 0, driftingBalances: 0 }),
    ),
    http.get(api(`${BASE}/reports/valuation`), () =>
      HttpResponse.json({ storeId: baixa.id, totalValue: '14820.50', rows: [
        { warehouseId: cozinha.id, warehouseCode: 'COZ', stockItemId: polvo.id, code: polvo.code, name: polvo.name,
          baseUnit: 'kg', quantity: '3.500', averageCost: '9.80', value: '34.30' },
      ] }),
    ),
    http.get(api(`${BASE}/reports/below-minimum`), () =>
      HttpResponse.json({ storeId: baixa.id, totalValue: '34.30', rows: [
        { warehouseId: cozinha.id, warehouseCode: 'COZ', stockItemId: polvo.id, code: polvo.code, name: polvo.name,
          baseUnit: 'kg', quantity: '3.500', minQuantity: '5.000', missing: '1.500', value: '34.30' },
      ] }),
    ),
    http.get(api(`${BASE}/suppliers`), () => HttpResponse.json(page(state.suppliers))),
    http.get(api(`${BASE}/purchase-orders`), () => HttpResponse.json(page(state.orders))),
    http.get(api(`${BASE}/receipts`), () => HttpResponse.json(page(state.receipts))),
    http.get(api(`${BASE}/recipes`), () => HttpResponse.json(page(state.recipes))),
    http.get(api(`${BASE}/transfers`), () => HttpResponse.json(page(state.transfers))),
  );
  return state;
}

/** The cell texts of a table's body rows, the first `columns` of each. */
export function rowsOf(table: HTMLElement, columns: number) {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) =>
      within(row)
        .getAllByRole('cell')
        .slice(0, columns)
        .map((cell) => cell.textContent.replace(/\s+/g, ' ')),
    );
}

/** The commands the area sent (anything but a read). */
export function commands(state: InventoryState) {
  return state.requests.filter((request) => request.method !== 'GET');
}
