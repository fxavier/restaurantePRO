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
