import { screen, waitFor, within } from '@testing-library/react';
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

    expect(await screen.findByLabelText('Código de empregado')).toBeInTheDocument();
    await services.api.GET('/api/v1/auth/session');

    expect(bearers).toEqual(['Bearer jwt-1']);
    expect(screen.queryByText(OFFLINE)).not.toBeInTheDocument();
  });

  it('renews the token before it expires, and the next call carries the new one', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    const forms = tokenEndpoint(token('jwt-1', 120), token('jwt-2', 120));
    const bearers = sessionEndpoint(() => HttpResponse.json({}));
    const { services } = await renderPos({ device: 'enrolled' });
    await screen.findByLabelText('Código de empregado');
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
    expect(screen.getByLabelText('Código de empregado')).toBeInTheDocument();
    // Nothing is sent without a token; the call fails as "no connection" (no handler exists: MSW would fail the test).
    await expect(services.api.GET('/api/v1/auth/session')).rejects.toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('shows a refused terminal as revoked and keeps its key: nothing is erased automatically', async () => {
    tokenEndpoint(refused(401, 'invalid_client'));
    const { devices, router } = await renderPos({ device: 'enrolled' });

    expect(await screen.findByRole('heading', { name: 'Terminal revogado ou inválido' })).toBeInTheDocument();
    expect(screen.getByText(/Um administrador tem de emitir um novo código de registo/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Código de empregado')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect((await devices.load())?.enrolment).toBeDefined();
  });

  it('on a refused call renews once; a refused renewal shows the revoked state, key kept', async () => {
    tokenEndpoint(token('jwt-1'), refused(400, 'unauthorized_client'));
    const bearers = sessionEndpoint(() =>
      HttpResponse.json(
        { title: 'Unauthorized', status: 401 },
        { status: 401, headers: { 'WWW-Authenticate': 'Bearer error="invalid_token"' } },
      ),
    );
    const { services, devices } = await renderPos({ device: 'enrolled' });
    await screen.findByLabelText('Código de empregado');

    await expect(services.api.GET('/api/v1/auth/session')).rejects.toMatchObject({ status: 401 });

    expect(bearers).toEqual(['Bearer jwt-1']);
    expect(await screen.findByRole('heading', { name: 'Terminal revogado ou inválido' })).toBeInTheDocument();
    expect((await devices.load())?.enrolment).toBeDefined();
  });

  it('"Registar de novo" asks first; cancelling keeps the key', async () => {
    tokenEndpoint(refused(401, 'invalid_client'));
    const { devices, user } = await renderPos({ device: 'enrolled' });
    await screen.findByRole('heading', { name: 'Terminal revogado ou inválido' });

    await user.click(screen.getByRole('button', { name: 'Registar de novo' }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect((await devices.load())?.enrolment).toBeDefined();
  });

  it('only the confirmed "Registar de novo" clears the device record and opens enrolment', async () => {
    tokenEndpoint(refused(401, 'invalid_client'));
    const { devices, user } = await renderPos({ device: 'enrolled' });
    await screen.findByRole('heading', { name: 'Terminal revogado ou inválido' });

    await user.click(screen.getByRole('button', { name: 'Registar de novo' }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Apagar e registar de novo' }));

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
