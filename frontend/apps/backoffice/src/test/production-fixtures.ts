import type { components } from '@restaurantpro/api-client';
import { http, HttpResponse } from 'msw';
import { api, baixa, terminal, terminalPage, type TerminalSummary } from './fixtures';

type Schemas = components['schemas'];
const DTO = 'com.xavier.backend.production.model.dto';
export type PrinterView = Schemas[`${typeof DTO}.PrinterView`];
export type PrintJobView = Schemas[`${typeof DTO}.PrintJobView`];
export type TicketView = Schemas[`${typeof DTO}.TicketView`];
export type DestinationRouteView = Schemas[`${typeof DTO}.DestinationRouteView`];
export type TerminalPrinterView = Schemas[`${typeof DTO}.TerminalPrinterView`];
export type PrinterPresetView = Schemas[`${typeof DTO}.PrinterPresetView`];

const PROFILE = { paperWidthMm: 80, columns: 48, cutMode: 'PARTIAL', feedLines: 4, statusProbe: true, drawerPulseMs: 100 } as const;

export function printer(id: string, code: string, name: string, extra: Partial<PrinterView> = {}): PrinterView {
  return {
    id,
    storeId: baixa.id,
    code,
    name,
    host: '192.168.1.181',
    port: 9100,
    codePage: 'CP860',
    active: true,
    mode: 'NORMAL',
    version: 3,
    profile: PROFILE,
    ...extra,
  };
}

export const kitchen = printer('cccc0000-0000-4000-8000-000000000001', 'COZ', 'Cozinha Quentes');
export const bar = printer('cccc0000-0000-4000-8000-000000000002', 'BAR', 'Bar Central', { host: '192.168.1.183' });
export const copa = printer('cccc0000-0000-4000-8000-000000000003', 'COPA', 'Copa', { host: '192.168.1.182' });

export const presets: PrinterPresetView[] = [
  { name: 'EPSON_TM_80', codePage: 'CP860', profile: PROFILE },
  { name: 'GENERIC_58', codePage: 'CP437', profile: { ...PROFILE, paperWidthMm: 58, columns: 32, cutMode: 'NONE' } },
];

export const caixa = terminal('aaaa0000-0000-4000-8000-000000000001', 'CX1', 'Caixa 1', 'ACTIVE');
export const sala = terminal('aaaa0000-0000-4000-8000-000000000002', 'SALA', 'Sala', 'ACTIVE');

export function job(id: string, extra: Partial<PrintJobView> = {}): PrintJobView {
  return {
    id,
    storeId: baixa.id,
    printerId: kitchen.id,
    ticketId: 'eeee0000-0000-4000-8000-000000000001',
    destination: 'COZINHA',
    sourceType: 'BATCH',
    kind: 'ORIGINAL',
    status: 'SENT',
    attempts: 1,
    createdAt: '2026-11-20T12:15:24Z',
    ...extra,
  };
}

export function ticket(id: string, extra: Partial<TicketView> = {}): TicketView {
  return {
    id,
    storeId: baixa.id,
    destination: 'SOBREMESAS',
    sourceType: 'BATCH',
    kind: 'ORIGINAL',
    routing: 'UNROUTED',
    suppressed: false,
    lines: ['1x Pudim'],
    createdAt: '2026-11-20T12:10:00Z',
    ...extra,
  };
}

const page = <T>(content: T[], number = 0, size = 20, totalElements = content.length) => ({
  content,
  page: number,
  size,
  totalElements,
});

export type ProductionState = {
  printers: PrinterView[];
  routes: DestinationRouteView[];
  assignments: TerminalPrinterView[];
  terminals: TerminalSummary[];
  jobs: PrintJobView[];
  tickets: TicketView[];
  /** Every request this stand-in saw: method, path with query, body and Idempotency-Key. */
  requests: { method: string; url: string; body: unknown; idempotencyKey: string | null }[];
};

/**
 * The production endpoints of one store as the backend answers them (openapi.json), over a state the tests read and
 * change. Lists filter by `status` and `routing` the way the server does, and report the filtered total.
 */
export function productionHandlers(initial: Partial<ProductionState> = {}) {
  const state: ProductionState = {
    printers: [kitchen, bar],
    routes: [{ destinationCode: 'COZINHA', printerId: kitchen.id }],
    assignments: [{ terminalId: caixa.id, printerId: bar.id }],
    terminals: [caixa, sala],
    jobs: [],
    tickets: [],
    requests: [],
    ...initial,
  };
  const record = async (request: Request) => {
    const text = await request.clone().text();
    const url = new URL(request.url);
    state.requests.push({
      method: request.method,
      url: url.pathname + url.search,
      body: text ? JSON.parse(text) : undefined,
      idempotencyKey: request.headers.get('Idempotency-Key'),
    });
    return url;
  };
  const base = '/api/v1/backoffice';
  const handlers = [
    http.get(api(`${base}/printers`), async ({ request }) => {
      await record(request);
      return HttpResponse.json(page(state.printers, 0, 100));
    }),
    http.get(api(`${base}/printer-profiles`), () => HttpResponse.json(presets)),
    http.get(api(`${base}/production-routes`), () => HttpResponse.json({ storeId: baixa.id, routes: state.routes })),
    http.get(api(`${base}/terminal-printers`), () =>
      HttpResponse.json({ storeId: baixa.id, assignments: state.assignments }),
    ),
    http.get(api(`${base}/terminals`), () => HttpResponse.json(terminalPage(state.terminals))),
    http.get(api(`${base}/print-jobs`), async ({ request }) => {
      const url = await record(request);
      const status = url.searchParams.get('status');
      const size = Number(url.searchParams.get('size') ?? 20);
      const number = Number(url.searchParams.get('page') ?? 0);
      const matching = state.jobs.filter((one) => !status || one.status === status);
      return HttpResponse.json(page(matching.slice(number * size, (number + 1) * size), number, size, matching.length));
    }),
    http.get(api(`${base}/production-tickets`), async ({ request }) => {
      const url = await record(request);
      const routing = url.searchParams.get('routing');
      const matching = state.tickets.filter((one) => !routing || one.routing === routing);
      return HttpResponse.json(page(matching, 0, Number(url.searchParams.get('size') ?? 20)));
    }),
  ];
  return { state, handlers, record };
}
