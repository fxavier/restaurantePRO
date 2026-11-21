import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { admin, api, signedIn, type SessionView } from './fixtures';
import { server } from './server';

type Schemas = components['schemas'];
export type CustomerCandidate = Schemas['com.xavier.backend.customers.model.dto.CustomerCandidate'];
export type CustomerDetails = Schemas['com.xavier.backend.customers.model.dto.CustomerDetails'];
export type CreditView = Schemas['com.xavier.backend.customers.CreditView'];
export type DebtRow = Schemas['com.xavier.backend.customers.model.dto.DebtRow'];
export type StatementView = Schemas['com.xavier.backend.customers.model.dto.StatementView'];
export type LoyaltyBalance = Schemas['com.xavier.backend.promotions.LoyaltyBalance'];
export type LoyaltyStatement = Schemas['com.xavier.backend.promotions.loyalty.model.dto.StatementView'];

/** MZN, as the company's currency arrives in every Money of the fixtures (never hard-coded in the app). */
export const mzn = (amount: string) => ({ amount, currency: 'MZN' });

/** A customer as the tests address it: with its id. */
export type Customer = CustomerDetails & { id: string; name: string };

export const lusitania: Customer = {
  id: 'c0000000-0000-4000-8000-000000000001',
  name: 'Lusitânia Tech, Lda.',
  taxId: '501234567',
  email: 'financeiro@lusitania.co.mz',
  notes: 'Fatura mensal',
  active: true,
  version: 3,
  phones: [{ id: 'p0000000-0000-4000-8000-000000000001', digits: '258841234567', label: 'Escritório', primary: true }],
  addresses: [
    {
      id: 'a0000000-0000-4000-8000-000000000001',
      kind: 'BILLING',
      line1: 'Av. Julius Nyerere 102',
      line2: '4.º andar',
      city: 'Maputo',
      postalCode: '1100',
      country: 'Moçambique',
      version: 0,
    },
  ],
};

export const tejo: Customer = {
  id: 'c0000000-0000-4000-8000-000000000002',
  name: 'Construtora Tejo & Filhos, SA',
  taxId: '509876543',
  active: true,
  version: 0,
  phones: [],
  addresses: [],
};

export function candidate(customer: CustomerDetails): CustomerCandidate {
  return {
    id: customer.id,
    name: customer.name,
    taxId: customer.taxId,
    phones: customer.phones?.map((phone) => phone.digits ?? '') ?? [],
    active: customer.active,
  };
}

export const lusitaniaCredit: CreditView = {
  limit: mzn('1000.00'),
  balance: mzn('450.20'),
  reserved: mzn('0.00'),
  available: mzn('549.80'),
  blocked: false,
};

export const lusitaniaDebt: DebtRow = {
  customerId: lusitania.id,
  name: lusitania.name,
  taxId: lusitania.taxId,
  balance: mzn('450.20'),
  days0To30: mzn('450.20'),
  days31To60: mzn('0.00'),
  days61To90: mzn('0.00'),
  over90: mzn('0.00'),
};

export const tejoDebt: DebtRow = {
  customerId: tejo.id,
  name: tejo.name,
  taxId: tejo.taxId,
  balance: mzn('620.00'),
  days0To30: mzn('0.00'),
  days31To60: mzn('400.00'),
  days61To90: mzn('0.00'),
  over90: mzn('220.00'),
};

export const lusitaniaStatement: StatementView = {
  customerId: lusitania.id,
  from: '1970-01-01T00:00:00Z',
  to: '2026-11-20T10:00:00Z',
  openingBalance: mzn('0.00'),
  closingBalance: mzn('450.20'),
  lines: [
    {
      entryId: 'e0000000-0000-4000-8000-000000000001',
      kind: 'DEBIT',
      sourceType: 'CREDIT_CHARGE',
      businessDate: '2026-11-02',
      occurredAt: '2026-11-02T20:15:00Z',
      description: 'Fatura FT 2026/1792',
      amount: mzn('490.00'),
      balance: mzn('490.00'),
      settlements: [],
    },
    {
      entryId: 'e0000000-0000-4000-8000-000000000002',
      kind: 'CREDIT',
      sourceType: 'PAYMENT',
      businessDate: '2026-11-10',
      occurredAt: '2026-11-10T09:00:00Z',
      description: 'Recebimento por transferência',
      amount: mzn('300.00'),
      balance: mzn('190.00'),
      settlements: [],
    },
    {
      entryId: 'e0000000-0000-4000-8000-000000000003',
      kind: 'DEBIT',
      sourceType: 'CREDIT_CHARGE',
      businessDate: '2026-11-18',
      occurredAt: '2026-11-18T13:00:00Z',
      description: 'Fatura FT 2026/1840',
      amount: mzn('260.20'),
      balance: mzn('450.20'),
      settlements: [],
    },
  ],
};

export const lusitaniaLoyalty: LoyaltyBalance = { customerId: lusitania.id, enrolled: true, points: 320, status: 'ACTIVE' };

export const lusitaniaLoyaltyStatement: LoyaltyStatement = {
  customerId: lusitania.id,
  from: '1970-01-01T00:00:00Z',
  to: '2026-11-20T10:00:00Z',
  openingBalance: 0,
  closingBalance: 320,
  lines: [
    {
      id: 'l0000000-0000-4000-8000-000000000001',
      kind: 'EARN',
      businessDate: '2026-11-02',
      occurredAt: '2026-11-02T20:15:00Z',
      points: 300,
      balance: 300,
    },
    {
      id: 'l0000000-0000-4000-8000-000000000002',
      kind: 'ADJUSTMENT',
      businessDate: '2026-11-12',
      occurredAt: '2026-11-12T10:00:00Z',
      points: 20,
      balance: 320,
      reason: 'Compensação por atraso',
    },
  ],
};

/** Everything a customers request carried, as the tests read it back. */
export type SeenRequest = { method: string; path: string; search: string; body: unknown; idempotencyKey: string | null; csrf: string | null };

async function seen(request: Request): Promise<SeenRequest> {
  const text = await request.clone().text();
  const url = new URL(request.url);
  return {
    method: request.method,
    path: url.pathname,
    search: url.search,
    body: text ? JSON.parse(text) : undefined,
    idempotencyKey: request.headers.get('Idempotency-Key'),
    csrf: request.headers.get('X-XSRF-TOKEN'),
  };
}

/** The customers' permissions on top of the administrator's (CUSTOMER_VIEW is already there). */
export const customersAdmin: SessionView = {
  ...admin,
  permissions: [
    ...admin.permissions,
    'PERM_CUSTOMER_MANAGE',
    'PERM_CREDIT_LIMIT_MANAGE',
    'PERM_LOYALTY_VIEW',
    'PERM_LOYALTY_ENROLL',
    'PERM_LOYALTY_ADJUST',
  ],
};

const BASE = '/api/v1/backoffice/customers';

/**
 * A signed-in user and the customers API as the backend answers it for two customers; each test overrides what it
 * needs with `server.use` after this. `requests` records every customers and loyalty request.
 */
export function customersServer({ session = customersAdmin }: { session?: SessionView } = {}) {
  const requests: SeenRequest[] = [];
  const details: Record<string, CustomerDetails> = { [lusitania.id]: lusitania, [tejo.id]: tejo };
  server.use(...signedIn({ session }));
  // Every request, whichever handler a test adds later answers it.
  server.events.removeAllListeners('request:start');
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname.startsWith('/api/v1/backoffice/')) void seen(request).then((r) => requests.push(r));
  });
  server.use(
    http.get(api(`${BASE}/debts`), () =>
      HttpResponse.json({ content: [lusitaniaDebt, tejoDebt], page: 0, size: 20, totalElements: 2 }),
    ),
    http.get(api(BASE), () => HttpResponse.json([candidate(lusitania), candidate(tejo)])),
    http.get(api(`${BASE}/:id`), ({ params }) => {
      const found = details[String(params.id)];
      return found ? HttpResponse.json(found) : HttpResponse.json({ status: 404, code: 'NOT_FOUND', title: 'Not found' }, { status: 404 });
    }),
    http.get(api(`${BASE}/:id/credit`), () => HttpResponse.json(lusitaniaCredit)),
    http.get(api(`${BASE}/:id/statement`), () => HttpResponse.json(lusitaniaStatement)),
    http.get(api('/api/v1/backoffice/loyalty/accounts/:id'), () => HttpResponse.json(lusitaniaLoyalty)),
    http.get(api('/api/v1/backoffice/loyalty/accounts/:id/statement'), () => HttpResponse.json(lusitaniaLoyaltyStatement)),
  );
  return { requests };
}

export const CUSTOMERS = '/customers';
export const customerUrl = (id: string) => `/customers?customerId=${id}`;
