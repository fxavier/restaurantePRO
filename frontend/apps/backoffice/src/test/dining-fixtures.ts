import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { admin, api, baixa, signedIn, type SessionView } from './fixtures';
import { sent, type Sent } from './organization-fixtures';
import { server } from './server';

type Schemas = components['schemas'];
export type RoomAreaSummary = Schemas['com.xavier.backend.dining.model.dto.RoomAreaSummary'];
export type RestaurantTableSummary = Schemas['com.xavier.backend.dining.model.dto.RestaurantTableSummary'];
export type DiningSettingsView = Schemas['com.xavier.backend.dining.model.dto.DiningSettingsView'];
export type ReservationView = Schemas['com.xavier.backend.dining.model.dto.ReservationView'];

export function area(id: string, code: string, name: string, extra: Partial<RoomAreaSummary> = {}): RoomAreaSummary {
  return { id, storeId: baixa.id, code, name, active: true, version: 0, ...extra };
}

export function table(id: string, areaId: string, code: string, seats: number, extra: Partial<RestaurantTableSummary> = {}): RestaurantTableSummary {
  return { id, storeId: baixa.id, areaId, code, seats, active: true, locked: false, version: 0, ...extra };
}

export const sala = area('a1a1a1a1-0000-4000-8000-000000000001', 'SALA', 'Sala principal');
export const esplanada = area('a2a2a2a2-0000-4000-8000-000000000002', 'ESPL', 'Esplanada');
const M1_ID = 'b1b1b1b1-0000-4000-8000-000000000001';
export const m1 = table(M1_ID, sala.id, 'M01', 4);
export const m2 = table('b2b2b2b2-0000-4000-8000-000000000002', sala.id, 'M02', 2, { locked: true });
export const e1 = table('b3b3b3b3-0000-4000-8000-000000000003', esplanada.id, 'E01', 6, { active: false });

export const settings: DiningSettingsView = { storeId: baixa.id, reservationWindowMinutes: 120, noShowGraceMinutes: 30, version: 2 };

export function reservation(id: string, extra: Partial<ReservationView> = {}): ReservationView {
  return {
    id,
    storeId: baixa.id,
    startsAt: '2026-11-20T18:30:00Z',
    durationMinutes: 90,
    partySize: 4,
    contactName: 'Joana Mabote',
    contactPhoneDigits: '258841234567',
    status: 'BOOKED',
    tableIds: [M1_ID],
    version: 0,
    ...extra,
  };
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 200, totalElements: content.length };
}

/**
 * One store's areas, tables, settings and reservations as the server keeps them: creates add, updates replace with
 * the next version, and every write and every reservations query is recorded.
 */
export function diningServer({
  session = admin,
  areas = [sala, esplanada],
  tables = [m1, m2, e1],
  diningSettings = settings,
  reservations = [] as ReservationView[],
}: {
  session?: SessionView;
  areas?: RoomAreaSummary[];
  tables?: RestaurantTableSummary[];
  diningSettings?: DiningSettingsView;
  reservations?: ReservationView[];
} = {}) {
  const state = {
    areas: [...areas],
    tables: [...tables],
    settings: { ...diningSettings },
    writes: [] as Sent[],
    reservationQueries: [] as string[],
  };
  const replace = <T extends { id?: string; version?: number }>(list: T[], id: unknown, body: object) => {
    const index = list.findIndex((item) => item.id === id);
    list[index] = { ...list[index]!, ...body, version: (list[index]!.version ?? 0) + 1 };
    return list[index]!;
  };
  server.use(...signedIn({ session }));
  server.use(
    http.get(api('/api/v1/backoffice/areas'), () => HttpResponse.json(page(state.areas))),
    http.get(api('/api/v1/backoffice/tables'), () => HttpResponse.json(page(state.tables))),
    http.get(api('/api/v1/backoffice/dining/settings/:storeId'), () => HttpResponse.json(state.settings)),
    http.get(api('/api/v1/backoffice/reservations'), ({ request }) => {
      state.reservationQueries.push(new URL(request.url).search);
      return HttpResponse.json(reservations);
    }),
    http.post(api('/api/v1/backoffice/areas'), async ({ request }) => {
      const write = await sent(request);
      state.writes.push(write);
      const created = { ...(write.body as RoomAreaSummary), id: `a9a9a9a9-0000-4000-8000-00000000000${state.areas.length}`, active: true, version: 0 };
      state.areas.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put(api('/api/v1/backoffice/areas/:id'), async ({ request, params }) => {
      const write = await sent(request);
      state.writes.push(write);
      return HttpResponse.json(replace(state.areas, params.id, write.body as object));
    }),
    http.post(api('/api/v1/backoffice/tables'), async ({ request }) => {
      const write = await sent(request);
      state.writes.push(write);
      const created = {
        ...(write.body as RestaurantTableSummary),
        id: `b9b9b9b9-0000-4000-8000-00000000000${state.tables.length}`,
        active: true,
        locked: false,
        version: 0,
      };
      state.tables.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put(api('/api/v1/backoffice/tables/:id'), async ({ request, params }) => {
      const write = await sent(request);
      state.writes.push(write);
      return HttpResponse.json(replace(state.tables, params.id, write.body as object));
    }),
    http.put(api('/api/v1/backoffice/dining/settings/:storeId'), async ({ request }) => {
      const write = await sent(request);
      state.writes.push(write);
      state.settings = { ...state.settings, ...(write.body as object), version: (state.settings.version ?? 0) + 1 };
      return HttpResponse.json(state.settings);
    }),
  );
  return state;
}
