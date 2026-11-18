import { delay, http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { components } from '@restaurantpro/api-client';

type EnrolmentResponse = components['schemas']['com.xavier.backend.organization.model.dto.DeviceEnrolmentResponse'];
type EnrolmentRequest = components['schemas']['com.xavier.backend.organization.model.dto.DeviceEnrolmentRequest'];

/**
 * Every request must have a handler: an unexpected call fails the test. The one exception is the home screen every
 * login reaches (`/tables`): an empty table map and a change stream held open, so a test about the PIN pad need not
 * stand in for the map. A test about the map replaces both with `tablesEndpoint` and `notificationsEndpoint`.
 */
export const server = setupServer(
  http.get('*/api/v1/pos/tables', () => HttpResponse.json({ storeId: '6f1c2a9e-0000-4000-8000-0000000000aa', areas: [] })),
  http.get('*/api/v1/pos/notifications', ({ request }) => openStream(request).response),
);

export const ISSUER = 'https://keycloak.test/realms/restaurantpro-devices';
export const TOKEN_URL = `${ISSUER}/protocol/openid-connect/token`;

export const ENROLMENT = {
  id: '6f1c2a9e-0000-4000-8000-000000000001',
  storeId: '6f1c2a9e-0000-4000-8000-0000000000aa',
  clientId: 'pos-6f1c2a9e-0000-4000-8000-000000000001',
} satisfies EnrolmentResponse;

/** `POST /api/v1/pos/enrolment`, typed from the contract; records the bodies it received. */
export function enrolmentEndpoint(answer: () => Response | Promise<Response>) {
  const bodies: EnrolmentRequest[] = [];
  server.use(
    http.post('*/api/v1/pos/enrolment', async ({ request }) => {
      bodies.push((await request.json()) as EnrolmentRequest);
      return answer();
    }),
  );
  return bodies;
}

export const enrolled = (body: EnrolmentResponse = ENROLMENT) => HttpResponse.json(body);

export const problem = (status: number, code: string, headers?: Record<string, string>) =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail: 'x', code },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );

/** The devices realm's token endpoint (Keycloak), answering from a queue whose last answer repeats. */
export function tokenEndpoint(...answers: (() => Response)[]) {
  const forms: URLSearchParams[] = [];
  server.use(
    http.post(TOKEN_URL, async ({ request }) => {
      forms.push(new URLSearchParams(await request.text()));
      const answer = answers.length > 1 ? answers.shift()! : answers[0]!;
      return answer();
    }),
  );
  return forms;
}

export const token = (value: string, expiresIn = 300) => () =>
  HttpResponse.json({ access_token: value, expires_in: expiresIn, token_type: 'Bearer' });
export const unreachable = () => () => HttpResponse.error();
export const refused = (status = 401, error = 'invalid_client') => () =>
  HttpResponse.json({ error, error_description: 'Invalid client or Invalid client credentials' }, { status });

type SessionView = components['schemas']['com.xavier.backend.identity.model.dto.SessionView'];
type PosLoginRequest = components['schemas']['com.xavier.backend.identity.model.dto.PosLoginRequest'];
type PinChangeRequest = components['schemas']['com.xavier.backend.identity.model.dto.PinChangeRequest'];

/** What the PIN login answers for the waiter Ana: her authorities in the terminal's store and the session token. */
export const SESSION = {
  kind: 'EMPLOYEE',
  userId: '6f1c2a9e-0000-4000-8000-0000000000e1',
  employeeId: '6f1c2a9e-0000-4000-8000-0000000000e1',
  terminalId: ENROLMENT.id,
  storeId: ENROLMENT.storeId,
  name: 'Ana Silva',
  mustChangePin: false,
  permissions: [`PERM_ORDER_CREATE@${ENROLMENT.storeId}`],
  posSession: 'pos-session-ana',
} satisfies SessionView;

/** A request as the backend saw it: the two credentials, the Idempotency-Key, the method, the URL and the JSON body. */
export type Seen<Body> = {
  method: string;
  url: string;
  bearer: string | null;
  posSession: string | null;
  idempotencyKey: string | null;
  body: Body;
  /** The body as sent, before JSON parsing loses a decimal's trailing zeros. */
  raw: string;
};

/** Every request any handler of these helpers saw, so a test can check what crossed the wire. */
export const wire: Seen<unknown>[] = [];

export function recording<Body>(
  method: 'get' | 'post' | 'put' | 'delete',
  path: string,
  answer: (body: Body, seen: Seen<Body>) => Response | Promise<Response>,
) {
  const seen: Seen<Body>[] = [];
  server.use(
    http[method](`*${path}`, async ({ request }) => {
      const text = await request.text();
      const entry: Seen<Body> = {
        method: request.method,
        url: request.url,
        bearer: request.headers.get('Authorization'),
        posSession: request.headers.get('X-Pos-Session'),
        idempotencyKey: request.headers.get('Idempotency-Key'),
        body: (text ? JSON.parse(text) : undefined) as Body,
        raw: text,
      };
      seen.push(entry);
      wire.push(entry);
      return answer(entry.body, entry);
    }),
  );
  return seen;
}

/** Answers from a queue whose last answer repeats. */
export function queue<Args extends unknown[]>(...answers: ((...args: Args) => Response | Promise<Response>)[]) {
  return (...args: Args) => (answers.length > 1 ? answers.shift()! : answers[0]!)(...args);
}

/** `POST /api/v1/auth/pos-login`. */
export const posLoginEndpoint = (answer: (body: PosLoginRequest) => Response | Promise<Response>) =>
  recording('post', '/api/v1/auth/pos-login', answer);

/** `POST /api/v1/auth/pos-pin`. */
export const choosePinEndpoint = (answer: (body: PinChangeRequest) => Response | Promise<Response>) =>
  recording('post', '/api/v1/auth/pos-pin', answer);

/** `POST /api/v1/auth/pos-logout` (204). */
export const posLogoutEndpoint = (answer: () => Response | Promise<Response> = () => new HttpResponse(null, { status: 204 })) =>
  recording<undefined>('post', '/api/v1/auth/pos-logout', answer);

/** `GET /api/v1/auth/pos-session`. */
export const posSessionEndpoint = (answer: () => Response | Promise<Response>) =>
  recording<undefined>('get', '/api/v1/auth/pos-session', answer);

export const session = (body: Partial<SessionView> = {}) => HttpResponse.json({ ...SESSION, ...body });

/** What the backend answers a refused PIN login, whatever the reason (wrong PIN, locked, unknown code): no code. */
export const loginRefused = () =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Unauthorized', status: 401, detail: 'Authentication is required.' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  );

type TableMap = components['schemas']['com.xavier.backend.dining.model.dto.TableMap'];

/** `GET /api/v1/pos/tables`, answering from a queue whose last answer repeats. */
export function tablesEndpoint(...answers: (() => Response | Promise<Response>)[]) {
  return recording<undefined>('get', '/api/v1/pos/tables', () => (answers.length > 1 ? answers.shift()! : answers[0]!)());
}

export const tableMap = (body: TableMap) => () => HttpResponse.json(body);

/** One open `GET /api/v1/pos/notifications`, as the backend writes it (Spring `SseEmitter`). */
export type ChangeStream = {
  bearer: string | null;
  posSession: string | null;
  /** A `change` event: `{topic, id}` only, never data (D60). */
  change(topic: string, id?: string): void;
  heartbeat(): void;
  /** The server completes the stream (the 30-minute emitter timeout, a shutdown). */
  end(): void;
  /** The connection breaks mid-stream (the LAN drops). */
  drop(): void;
  /** The client cancelled its read: it closed the stream. */
  readonly closed: boolean;
};

function openStream(request: Request): { stream: ChangeStream; response: Response } {
  const encoder = new TextEncoder();
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  let closed = false;
  const write = (text: string) => {
    if (!closed) controller.enqueue(encoder.encode(text));
  };
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
      // The answer starts with a first chunk (MSW answers the fetch only then): the backend's opening comment.
      c.enqueue(encoder.encode(':ok\n\n'));
    },
    cancel: () => void (closed = true),
  });
  const stream: ChangeStream = {
    bearer: request.headers.get('Authorization'),
    posSession: request.headers.get('X-Pos-Session'),
    change: (topic, id) => write(`event:change\ndata:${JSON.stringify(id ? { topic, id } : { topic })}\n\n`),
    heartbeat: () => write(':heartbeat\n\n'),
    end() {
      if (closed) return;
      closed = true;
      controller.close();
    },
    drop() {
      if (closed) return;
      closed = true;
      controller.error(new TypeError('network error'));
    },
    get closed() {
      return closed;
    },
  };
  // Chunked, as Spring streams an emitter; without it a client's cancel races the mock socket's close in undici.
  const headers = { 'Content-Type': 'text/event-stream', 'Transfer-Encoding': 'chunked' };
  return { stream, response: new HttpResponse(body, { headers }) };
}

/**
 * `GET /api/v1/pos/notifications`, answering from a queue whose last answer repeats: `'stream'` opens a stream the
 * test drives (in `streams`), starting with the backend's opening comment `:ok`; `'no-answer'` never answers (a
 * backend that hangs); a function answers otherwise (a refusal, a network error). `calls` counts every request.
 */
export function notificationsEndpoint(...answers: ('stream' | 'no-answer' | (() => Response))[]) {
  const queue = answers.length > 0 ? answers : ['stream' as const];
  const streams: ChangeStream[] = [];
  const calls = { count: 0 };
  server.use(
    http.get('*/api/v1/pos/notifications', async ({ request }) => {
      calls.count += 1;
      const answer = queue.length > 1 ? queue.shift()! : queue[0]!;
      if (typeof answer === 'function') return answer();
      if (answer === 'no-answer') await delay('infinite');
      const { stream, response } = openStream(request);
      streams.push(stream);
      return response;
    }),
  );
  return { streams, calls };
}
