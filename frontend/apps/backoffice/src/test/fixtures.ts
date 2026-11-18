import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';

type Schemas = components['schemas'];
export type SessionView = Schemas['com.xavier.backend.identity.model.dto.SessionView'];
export type TenantChoice = Schemas['com.xavier.backend.identity.model.dto.TenantChoice'];
export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];
type StorePage = Schemas['com.xavier.backend.shared.web.PageResponseCom.xavier.backend.organization.model.dto.StoreSummary'];
export type TerminalSummary = Schemas['com.xavier.backend.organization.model.dto.TerminalSummary'];
type TerminalPage = Schemas['com.xavier.backend.shared.web.PageResponseCom.xavier.backend.organization.model.dto.TerminalSummary'];

/** Requests are absolute in tests: Node's fetch needs an origin. */
export const ORIGIN = 'http://localhost';
export const api = (path: string) => `${ORIGIN}${path}`;

export const companyA = {
  tenantId: 'aaaaaaaa-0000-4000-8000-000000000001',
  code: 'rest-a',
  legalName: 'Restaurante A, Lda',
  kind: 'ACCOUNT',
  status: 'ACTIVE',
} satisfies TenantChoice;

export const companyB = {
  tenantId: 'bbbbbbbb-0000-4000-8000-000000000002',
  code: 'rest-b',
  legalName: 'Restaurante B, Lda',
  kind: 'ACCOUNT',
  status: 'ACTIVE',
} satisfies TenantChoice;

/** The session names the company the request was resolved in (FE-F08). */
export function sessionIn(company: TenantChoice, session: Partial<SessionView> = {}): SessionView {
  return { ...admin, tenantId: company.tenantId, tenantName: company.legalName, ...session };
}

export const admin: SessionView = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  userId: '0b6c1b0e-0000-4000-8000-000000000001',
  permissions: ['TERMINAL_MANAGE', 'STORE_MANAGE'],
  tenantId: companyA.tenantId,
  tenantName: companyA.legalName,
};

export function store(id: string, name: string, code = id): StoreSummary {
  return { id, code, name, active: true, timezone: 'Africa/Maputo', version: 0 };
}

export const baixa = store('11111111-0000-4000-8000-000000000001', 'Loja Baixa', 'BAIXA');
export const sommerschield = store('22222222-0000-4000-8000-000000000002', 'Loja Sommerschield', 'SOMM');

export function storePage(content: StoreSummary[]): StorePage {
  return { content, page: 0, size: 100, totalElements: content.length };
}

export function terminal(id: string, code: string, name: string, status = 'REGISTERED', storeId = baixa.id,
  kind = 'POS'): TerminalSummary {
  return { id, storeId, code, name, status, kind };
}

export function terminalPage(content: TerminalSummary[], page = 0, size = 50, totalElements = content.length): TerminalPage {
  return { content, page, size, totalElements };
}

export function problem(status: number, code: string | undefined, title = code ?? `HTTP ${status}`,
  extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) {
  return HttpResponse.json({ type: 'about:blank', title, status, ...(code ? { code } : {}), ...extra }, {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  });
}

/** The backend's step-up challenge (D184, RFC 9470): 401 MFA_STEP_UP_REQUIRED with the level and the maximum age. */
export function stepUpRequired(maxAge = 300) {
  return problem(401, 'MFA_STEP_UP_REQUIRED', 'Step-up required', { acrValues: '2', maxAge }, {
    'WWW-Authenticate': `Bearer error="insufficient_user_authentication", acr_values="2", max_age=${maxAge}`,
  });
}

/** A signed-in administrator of one company with two stores. */
export function signedIn({
  session = admin,
  tenants = [companyA],
  stores = [baixa, sommerschield],
}: { session?: SessionView; tenants?: TenantChoice[]; stores?: StoreSummary[] } = {}) {
  return [
    http.get(api('/api/v1/auth/session'), () => HttpResponse.json(session)),
    http.get(api('/api/v1/auth/tenants'), () => HttpResponse.json(tenants)),
    http.get(api('/api/v1/backoffice/stores'), () => HttpResponse.json(storePage(stores))),
    http.get(api('/api/v1/backoffice/terminals'), () => HttpResponse.json(terminalPage([]))),
  ];
}
