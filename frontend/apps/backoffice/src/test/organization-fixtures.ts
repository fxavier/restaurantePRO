import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { admin, api, baixa, signedIn, sommerschield, storePage, type SessionView, type StoreSummary } from './fixtures';
import { server } from './server';

type Schemas = components['schemas'];
export type CompanyProfileView = Schemas['com.xavier.backend.organization.model.dto.CompanyProfileView'];

export const companyProfile: CompanyProfileView = {
  name: 'Restaurante A, Lda',
  taxId: '400123456',
  country: 'MZ',
  currency: 'MZN',
  version: 3,
};

/** The administrator who may also read and edit the company profile (COMPANY_MANAGE). */
export const companyAdmin: SessionView = { ...admin, permissions: [...admin.permissions, 'PERM_COMPANY_MANAGE'] };

export type Sent = { method: string; path: string; body: unknown; idempotencyKey: string | null; csrf: string | null };

export async function sent(request: Request): Promise<Sent> {
  const text = await request.clone().text();
  const url = new URL(request.url);
  return {
    method: request.method,
    path: url.pathname + url.search,
    body: text ? JSON.parse(text) : undefined,
    idempotencyKey: request.headers.get('Idempotency-Key'),
    csrf: request.headers.get('X-XSRF-TOKEN'),
  };
}

/**
 * The company's stores and profile as the server keeps them: a create adds a store, an update replaces it with the
 * next version, and every write is recorded in `writes`.
 */
export function organizationServer(
  { session = companyAdmin, stores = [baixa, sommerschield], profile = companyProfile }:
    { session?: SessionView; stores?: StoreSummary[]; profile?: CompanyProfileView } = {},
) {
  const state = { stores: [...stores], profile: { ...profile }, writes: [] as Sent[], storeReads: 0 };
  server.use(...signedIn({ session, stores }));
  server.use(
    http.get(api('/api/v1/backoffice/stores'), () => {
      state.storeReads += 1;
      return HttpResponse.json(storePage(state.stores));
    }),
    http.get(api('/api/v1/backoffice/company-profile'), () => HttpResponse.json(state.profile)),
    http.post(api('/api/v1/backoffice/stores'), async ({ request }) => {
      const write = await sent(request);
      state.writes.push(write);
      const body = write.body as { code: string; name: string; timezone: string; businessDayCutoff?: string };
      const created: StoreSummary = {
        id: `33333333-0000-4000-8000-00000000000${state.stores.length + 1}`,
        active: true,
        currency: state.profile.currency ?? 'MZN',
        version: 0,
        businessDayCutoff: body.businessDayCutoff ?? '00:00:00',
        code: body.code,
        name: body.name,
        timezone: body.timezone,
      };
      state.stores.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put(api('/api/v1/backoffice/stores/:id'), async ({ request, params }) => {
      const write = await sent(request);
      state.writes.push(write);
      const index = state.stores.findIndex((store) => store.id === params.id);
      const updated = { ...state.stores[index]!, ...(write.body as object), version: state.stores[index]!.version + 1 };
      state.stores[index] = updated;
      return HttpResponse.json(updated);
    }),
    http.put(api('/api/v1/backoffice/company-profile'), async ({ request }) => {
      const write = await sent(request);
      state.writes.push(write);
      state.profile = { ...(write.body as CompanyProfileView), version: (state.profile.version ?? 0) + 1 };
      return HttpResponse.json(state.profile);
    }),
  );
  return state;
}
