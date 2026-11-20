import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { api, baixa, signedIn, type SessionView } from './fixtures';
import { server } from './server';

type Schemas = components['schemas'];
export type ZoneView = Schemas['com.xavier.backend.orders.delivery.model.dto.ZoneView'];
export type DeliveryView = Schemas['com.xavier.backend.orders.delivery.model.dto.DeliveryView'];
export type ItemSummary = Schemas['com.xavier.backend.catalog.model.dto.ItemSummary'];
export type ItemVariantSummary = Schemas['com.xavier.backend.catalog.model.dto.ItemVariantSummary'];
export type EmployeeSummary = Schemas['com.xavier.backend.identity.model.dto.EmployeeSummary'];

export function item(id: string, code: string, name: string, kind = 'FEE'): ItemSummary {
  return { id, code, name, kind, availability: 'AVAILABLE', soldByWeight: false, version: 0 };
}

/** Two delivery fees and a dish, which is no fee and so never offered as one. */
export const feeNear = item('f0000000-0000-4000-8000-000000000001', 'TX-PERTO', 'Taxa de entrega perto');
export const feeFar = item('f0000000-0000-4000-8000-000000000002', 'TX-LONGE', 'Taxa de entrega longe');
export const dish = item('f0000000-0000-4000-8000-000000000003', 'PRATO-1', 'Frango à zambeziana', 'STANDARD');
export const feeFarNight: ItemVariantSummary = {
  id: 'f1000000-0000-4000-8000-000000000001',
  itemId: feeFar.id,
  code: 'NOITE',
  name: 'Noturna',
  version: 0,
};

export function zone(id: string, code: string, name: string, feeItemId: string, extra: Partial<ZoneView> = {}): ZoneView {
  return { id, storeId: baixa.id, code, name, feeItemId, active: true, version: 0, ...extra };
}

export const centro = zone('20000000-0000-4000-8000-000000000001', 'Z1', 'Centro', feeNear.id);
export const polana = zone('20000000-0000-4000-8000-000000000002', 'Z2', 'Polana', feeFar.id, {
  feeVariantId: feeFarNight.id,
  active: false,
});

export const joao: EmployeeSummary = { id: '30000000-0000-4000-8000-000000000001', code: 'E01', name: 'João Pereira', active: true, locked: false, version: 0 };
export const tiago: EmployeeSummary = { id: '30000000-0000-4000-8000-000000000002', code: 'E02', name: 'Tiago Ramos', active: true, locked: false, version: 0 };

export function delivery(orderNumber: number, status: DeliveryView['status'], extra: Partial<DeliveryView> = {}): DeliveryView {
  return {
    orderId: `40000000-0000-4000-8000-${String(orderNumber).padStart(12, '0')}`,
    orderNumber,
    storeId: baixa.id,
    status,
    zoneId: centro.id,
    zoneCode: centro.code,
    promisedAt: '2026-11-20T18:30:00Z',
    history: [],
    version: 0,
    ...extra,
  };
}

export function page<T>(content: T[], number = 0, size = 50, totalElements = content.length) {
  return { content, page: number, size, totalElements };
}

/** What the zones screen reads: the session, the stores, the store's zones and the catalog's items. */
export function zonesServer({ zones = [centro, polana], items = [feeNear, feeFar, dish], session }: { zones?: ZoneView[]; items?: ItemSummary[]; session?: SessionView } = {}) {
  const state = { zones: [...zones], lists: [] as string[], itemLists: [] as string[] };
  server.use(...signedIn(session ? { session } : {}));
  server.use(
    http.get(api('/api/v1/backoffice/delivery-zones'), ({ request }) => {
      state.lists.push(new URL(request.url).search);
      return HttpResponse.json(state.zones);
    }),
    http.get(api('/api/v1/backoffice/catalog/items'), ({ request }) => {
      state.itemLists.push(new URL(request.url).search);
      return HttpResponse.json(page(items, 0, 200));
    }),
    http.get(api('/api/v1/backoffice/catalog/items/:id/variants'), ({ params }) =>
      HttpResponse.json(params.id === feeFar.id ? [feeFarNight] : []),
    ),
  );
  return state;
}

/** What the report reads: the session, the stores, the store's employees and its deliveries. */
export function reportServer({ deliveries = [] as DeliveryView[], employees = [joao, tiago], totalElements }: { deliveries?: DeliveryView[]; employees?: EmployeeSummary[]; totalElements?: number } = {}) {
  const state = { queries: [] as URLSearchParams[] };
  server.use(...signedIn());
  server.use(
    http.get(api('/api/v1/backoffice/employees'), () => HttpResponse.json(page(employees, 0, 200))),
    http.get(api('/api/v1/backoffice/deliveries'), ({ request }) => {
      const query = new URL(request.url).searchParams;
      state.queries.push(query);
      return HttpResponse.json(page(deliveries, Number(query.get('page') ?? 0), 50, totalElements ?? deliveries.length));
    }),
  );
  return state;
}
