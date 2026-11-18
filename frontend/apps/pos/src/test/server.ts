import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { components } from '@restaurantpro/api-client';

type EnrolmentResponse = components['schemas']['com.xavier.backend.organization.model.dto.DeviceEnrolmentResponse'];
type EnrolmentRequest = components['schemas']['com.xavier.backend.organization.model.dto.DeviceEnrolmentRequest'];

/** Every request must have a handler: an unexpected call fails the test. */
export const server = setupServer();

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

/** A request as the backend saw it: the two credentials, the URL and the JSON body. */
export type Seen<Body> = { url: string; bearer: string | null; posSession: string | null; body: Body };

/** Every request any handler of these helpers saw, so a test can check what crossed the wire. */
export const wire: Seen<unknown>[] = [];

function recording<Body>(method: 'get' | 'post', path: string, answer: (body: Body) => Response | Promise<Response>) {
  const seen: Seen<Body>[] = [];
  server.use(
    http[method](`*${path}`, async ({ request }) => {
      const text = await request.text();
      const entry: Seen<Body> = {
        url: request.url,
        bearer: request.headers.get('Authorization'),
        posSession: request.headers.get('X-Pos-Session'),
        body: (text ? JSON.parse(text) : undefined) as Body,
      };
      seen.push(entry);
      wire.push(entry);
      return answer(entry.body);
    }),
  );
  return seen;
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
