import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { refused, server, token, tokenEndpoint, unreachable } from './test/server';
import { renderPos } from './test/render-pos';

const OFFLINE = 'Sem ligação — nenhuma operação é confirmada';

/** A POS API call the backend answers with `answer`; records the bearer of every request. */
function sessionEndpoint(answer: () => Response) {
  const bearers: (string | null)[] = [];
  server.use(
    http.get('*/api/v1/auth/session', ({ request }) => {
      bearers.push(request.headers.get('Authorization'));
      return answer();
    }),
  );
  return bearers;
}

describe('POS shell', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('boots in Portuguese and sends a terminal with no device key to enrolment', async () => {
    await renderPos({ path: '/login' });

    expect(await screen.findByRole('heading', { level: 2, name: 'Registar este terminal' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'restaurantPRO — Ponto de venda' })).toBeInTheDocument();
  });

  it('gets the terminal token for an enrolled device and sends it as the bearer', async () => {
    tokenEndpoint(token('jwt-1'));
    const bearers = sessionEndpoint(() => HttpResponse.json({}));
    const { services } = await renderPos({ path: '/', device: 'enrolled' });

    expect(await screen.findByText('Terminal registado.')).toBeInTheDocument();
    await services.api.GET('/api/v1/auth/session');

    expect(bearers).toEqual(['Bearer jwt-1']);
    expect(screen.queryByText(OFFLINE)).not.toBeInTheDocument();
  });

  it('renews the token before it expires, and the next call carries the new one', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    const forms = tokenEndpoint(token('jwt-1', 120), token('jwt-2', 120));
    const bearers = sessionEndpoint(() => HttpResponse.json({}));
    const { services } = await renderPos({ device: 'enrolled' });
    await screen.findByText('Terminal registado.');
    await waitFor(() => expect(forms).toHaveLength(1));

    await vi.advanceTimersByTimeAsync(60_000); // 60 s left of 120

    await waitFor(() => expect(forms).toHaveLength(2));
    await services.api.GET('/api/v1/auth/session');
    expect(bearers).toEqual(['Bearer jwt-2']);
  });

  it('shows "Sem ligação" while Keycloak is unreachable, and sends no API call', async () => {
    tokenEndpoint(unreachable());
    const { services } = await renderPos({ device: 'enrolled' });

    expect(await screen.findByRole('alert')).toHaveTextContent(OFFLINE);
    expect(screen.getByText('Terminal registado.')).toBeInTheDocument();
    // Nothing is sent without a token; the call fails as "no connection" (no handler exists: MSW would fail the test).
    await expect(services.api.GET('/api/v1/auth/session')).rejects.toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('returns a revoked terminal to enrolment, with an explanation, and forgets its key', async () => {
    tokenEndpoint(refused(401, 'invalid_client'));
    const { devices } = await renderPos({ device: 'enrolled' });

    expect(await screen.findByRole('heading', { level: 2, name: 'Registar este terminal' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Este terminal foi desativado. Peça um novo código de registo ao administrador.',
    );
    await expect(devices.load()).resolves.toBeUndefined();
  });

  it('on a refused call renews once; a refused renewal means revoked, so back to enrolment', async () => {
    tokenEndpoint(token('jwt-1'), refused(400, 'unauthorized_client'));
    const bearers = sessionEndpoint(() =>
      HttpResponse.json({ title: 'Unauthorized', status: 401 }, { status: 401 }),
    );
    const { services, devices } = await renderPos({ device: 'enrolled' });
    await screen.findByText('Terminal registado.');

    await expect(services.api.GET('/api/v1/auth/session')).rejects.toMatchObject({ status: 401 });

    expect(bearers).toEqual(['Bearer jwt-1']);
    expect(await screen.findByRole('heading', { level: 2, name: 'Registar este terminal' })).toBeInTheDocument();
    await expect(devices.load()).resolves.toBeUndefined();
  });

  it('keeps the token out of localStorage and sessionStorage', async () => {
    tokenEndpoint(token('jwt-1'));
    const { services } = await renderPos({ device: 'enrolled' });
    await waitFor(() => expect(services.tokens.accessToken()).toBe('jwt-1'));

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});
