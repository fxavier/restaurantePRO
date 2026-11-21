import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { admin, api, baixa, problem, signedIn, sommerschield, type SessionView } from './fixtures';

type Schemas = components['schemas'];
type DTO = 'com.xavier.backend.identity.model.dto';
export type EmployeeSummary = Schemas[`${DTO}.EmployeeSummary`];
export type RoleSummary = Schemas[`${DTO}.RoleSummary`];
export type UserSummary = Schemas[`${DTO}.UserSummary`];
export type AssignmentSummary = Schemas[`${DTO}.AssignmentSummary`];
export type SupportGrantSummary = Schemas[`${DTO}.SupportGrantSummary`];

export function employee(id: string, code: string, name: string, extra: Partial<EmployeeSummary> = {}): EmployeeSummary {
  return { id, code, name, active: true, locked: false, version: 0, ...extra };
}

export function role(id: string, name: string, permissions: RoleSummary['permissions'] = [], version = 0): RoleSummary {
  return { id, name, permissions, version };
}

export function account(id: string, username: string, extra: Partial<UserSummary> = {}): UserSummary {
  return { id, username, active: true, support: false, roleIds: [], version: 0, ...extra };
}

export function grant(id: string, accountId: string, extra: Partial<SupportGrantSummary> = {}): SupportGrantSummary {
  return {
    id,
    accountId,
    status: 'ACTIVE',
    reason: 'Erro na impressão',
    validFrom: '2026-11-20T08:00:00Z',
    validUntil: '2026-11-20T10:00:00Z',
    version: 0,
    ...extra,
  };
}

export function page<T>(content: T[], number = 0, size = 20, totalElements = content.length) {
  return { content, page: number, size, totalElements };
}

export const maria = employee('e1000000-0000-4000-8000-000000000001', 'EMP-01', 'Maria Sitoe');
export const joao = employee('e1000000-0000-4000-8000-000000000002', 'EMP-04', 'João Macuácua', { locked: true });
export const rui = employee('e1000000-0000-4000-8000-000000000003', 'EMP-11', 'Rui Bila', { active: false });

const GERENTE = 'c1000000-0000-4000-8000-000000000001';
export const gerente = role(GERENTE, 'Gerente', ['ORDER_VOID_APPROVE', 'CASH_VIEW'], 3);
export const empregadoSala = role('c1000000-0000-4000-8000-000000000002', 'Empregado de sala', ['ORDER_EDIT']);

export const ana = account('a1000000-0000-4000-8000-000000000001', 'ana.admin', { roleIds: [GERENTE] });
export const suporte = account('a1000000-0000-4000-8000-000000000002', 'suporte.ops', { support: true });

/** The administrator with the four permissions of the people screen's tabs. */
export const peopleAdmin: SessionView = {
  ...admin,
  permissions: [...admin.permissions, 'PERM_ROLE_MANAGE', 'PERM_USER_MANAGE', 'PERM_SUPPORT_ACCESS_MANAGE'],
};

/** What the handlers saw: the path and query, the JSON body and the two headers the contract cares about. */
export type Seen = { method: string; url: string; body: unknown; idempotencyKey: string | null };

async function seen(request: Request): Promise<Seen> {
  const text = await request.clone().text();
  const url = new URL(request.url);
  return {
    method: request.method,
    url: url.pathname + url.search,
    body: text ? JSON.parse(text) : undefined,
    idempotencyKey: request.headers.get('Idempotency-Key'),
  };
}

type PeopleState = {
  employees: EmployeeSummary[];
  roles: RoleSummary[];
  users: UserSummary[];
  grants: SupportGrantSummary[];
  assignments: AssignmentSummary[];
  requests: Seen[];
};

/**
 * The BackOffice people endpoints over one in-memory company, as the server would answer them; `state.requests` keeps
 * every request in order. A test overrides one answer with a later `server.use`.
 */
export function peopleHandlers({
  session = peopleAdmin,
  ...initial
}: Partial<Omit<PeopleState, 'requests'>> & { session?: SessionView } = {}) {
  const state: PeopleState = {
    employees: [maria, joao, rui],
    roles: [gerente, empregadoSala],
    users: [ana, suporte],
    grants: [],
    assignments: [],
    requests: [],
    ...initial,
  };
  const base = '/api/v1/backoffice';
  const record = async (request: Request) => {
    const entry = await seen(request);
    state.requests.push(entry);
    return entry;
  };
  const handlers = [
    ...signedIn({ session, stores: [baixa, sommerschield] }),
    http.get(api(`${base}/employees`), async ({ request }) => {
      await record(request);
      return HttpResponse.json(page(state.employees));
    }),
    http.get(api(`${base}/roles`), async ({ request }) => {
      await record(request);
      return HttpResponse.json(page(state.roles));
    }),
    http.get(api(`${base}/users`), async ({ request }) => {
      await record(request);
      return HttpResponse.json(page(state.users));
    }),
    http.get(api(`${base}/support-grants`), async ({ request }) => {
      await record(request);
      return HttpResponse.json(page(state.grants));
    }),
    http.get(api(`${base}/employees/:id/assignments`), async ({ request, params }) => {
      await record(request);
      return HttpResponse.json(state.assignments.filter((a) => a.employeeId === params.id));
    }),
    http.post(api(`${base}/employees`), async ({ request }) => {
      const { body } = await record(request);
      const { code, name } = body as { code: string; name: string };
      const hired = employee(`e2000000-0000-4000-8000-00000000000${state.employees.length}`, code, name);
      state.employees.push(hired);
      return HttpResponse.json(hired, { status: 201 });
    }),
    http.put(api(`${base}/employees/:id`), async ({ request, params }) => {
      const { body } = await record(request);
      const { name, active, version } = body as { name: string; active: boolean; version: number };
      const current = state.employees.find((e) => e.id === params.id)!;
      if (current.version !== version) return problem(409, 'VERSION_CONFLICT', 'Version conflict');
      const updated = { ...current, name, active, version: version + 1 };
      state.employees = state.employees.map((e) => (e.id === params.id ? updated : e));
      return HttpResponse.json(updated);
    }),
    http.post(api(`${base}/employees/:id/reset-pin`), async ({ request, params }) => {
      await record(request);
      return HttpResponse.json(state.employees.find((e) => e.id === params.id));
    }),
    http.post(api(`${base}/employees/:id/assignments`), async ({ request, params }) => {
      const { body } = await record(request);
      const added = {
        id: `d2000000-0000-4000-8000-00000000000${state.assignments.length}`,
        employeeId: String(params.id),
        ...(body as { storeId: string; roleId: string }),
      };
      state.assignments.push(added);
      return HttpResponse.json(added, { status: 201 });
    }),
    http.delete(api(`${base}/employees/:id/assignments/:assignmentId`), async ({ request, params }) => {
      await record(request);
      state.assignments = state.assignments.filter((a) => a.id !== params.assignmentId);
      return new HttpResponse(null, { status: 204 });
    }),
    http.post(api(`${base}/roles`), async ({ request }) => {
      const { body } = await record(request);
      const { name, permissions } = body as { name: string; permissions: RoleSummary['permissions'] };
      const created = role(`c2000000-0000-4000-8000-00000000000${state.roles.length}`, name, permissions);
      state.roles.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put(api(`${base}/roles/:id`), async ({ request, params }) => {
      const { body } = await record(request);
      const { name, permissions, version } = body as { name: string; permissions: RoleSummary['permissions']; version: number };
      const current = state.roles.find((r) => r.id === params.id)!;
      if (current.version !== version) return problem(409, 'VERSION_CONFLICT', 'Version conflict');
      const updated = role(current.id!, name, permissions, version + 1);
      state.roles = state.roles.map((r) => (r.id === params.id ? updated : r));
      return HttpResponse.json(updated);
    }),
    http.post(api(`${base}/users`), async ({ request }) => {
      const { body } = await record(request);
      const created = account(`a2000000-0000-4000-8000-00000000000${state.users.length}`, '', body as Partial<UserSummary>);
      state.users.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
    http.put(api(`${base}/users/:id`), async ({ request, params }) => {
      const { body } = await record(request);
      const current = state.users.find((u) => u.id === params.id)!;
      const changes = body as Partial<UserSummary>;
      if (current.version !== changes.version) return problem(409, 'VERSION_CONFLICT', 'Version conflict');
      const updated = { ...current, employeeId: undefined, ...changes, version: current.version! + 1 };
      state.users = state.users.map((u) => (u.id === params.id ? updated : u));
      return HttpResponse.json(updated);
    }),
    http.post(api(`${base}/support-grants`), async ({ request }) => {
      const { body } = await record(request);
      const { accountId, reason } = body as { accountId: string; reason: string };
      const granted = grant(`f2000000-0000-4000-8000-00000000000${state.grants.length}`, accountId, { reason });
      state.grants.push(granted);
      return HttpResponse.json(granted, { status: 201 });
    }),
    http.post(api(`${base}/support-grants/:id/revoke`), async ({ request, params }) => {
      await record(request);
      state.grants = state.grants.map((g) => (g.id === params.id ? { ...g, status: 'REVOKED' as const } : g));
      return HttpResponse.json(state.grants.find((g) => g.id === params.id));
    }),
    http.post(api(`${base}/employees/:id/unlock`), async ({ request, params }) => {
      await record(request);
      state.employees = state.employees.map((e) => (e.id === params.id ? { ...e, locked: false } : e));
      return HttpResponse.json(state.employees.find((e) => e.id === params.id));
    }),
  ];
  return { state, handlers };
}
