import { act, screen, waitFor, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  choosePinEndpoint,
  loginRefused,
  posLoginEndpoint,
  posLogoutEndpoint,
  posSessionEndpoint,
  problem,
  SESSION,
  session,
  token,
  tokenEndpoint,
  wire,
} from '../../test/server';
import { renderPos } from '../../test/render-pos';

/** Distinctive values, so a search for them anywhere cannot match by accident. */
const CODE = '1042';
const PIN = '739164';
const NEW_PIN = '582047';

type Rendered = Awaited<ReturnType<typeof renderPos>>;
type User = Rendered['user'];

const codeInput = () => screen.getByLabelText('Código de empregado');
const pinInput = () => screen.getByLabelText('PIN');
const pad = () => screen.getByRole('group', { name: 'Teclado numérico' });
const tap = (user: User, digits: string) =>
  digits.split('').reduce<Promise<void>>(
    (previous, digit) => previous.then(() => user.click(within(pad()).getByRole('button', { name: digit }))),
    Promise.resolve(),
  );

/** An enrolled terminal with its token, on the PIN pad. */
async function atLogin(options: Parameters<typeof renderPos>[0] = {}, tokens = [token('jwt-1')]) {
  tokenEndpoint(...tokens);
  const rendered = await renderPos({ device: 'enrolled', path: '/login', ...options });
  await screen.findByLabelText('Código de empregado');
  return rendered;
}

/** Code and PIN on the on-screen pad, then "Entrar". */
async function logIn(user: User, pin = PIN) {
  await tap(user, CODE);
  await user.click(screen.getByRole('button', { name: 'Seguinte' }));
  await tap(user, pin);
  await user.click(screen.getByRole('button', { name: 'Entrar' }));
}

async function loggedIn(options: Parameters<typeof renderPos>[0] = {}) {
  posLoginEndpoint(() => session());
  const rendered = await atLogin(options);
  await logIn(rendered.user);
  await screen.findByRole('button', { name: 'Trocar empregado' });
  return rendered;
}

/** The PIN and the session token must not be anywhere the browser keeps or shows things. */
function expectNoSecretKept({ queryClient, router }: Rendered, secrets: string[]) {
  const kept = JSON.stringify({
    local: { ...localStorage },
    session: { ...sessionStorage },
    queryKeys: queryClient.getQueryCache().getAll().map((query) => query.queryKey),
    mutations: queryClient.getMutationCache().getAll().map((mutation) => mutation.state.variables),
    location: router.state.location,
    urls: wire.map((request) => request.url),
  });
  for (const secret of secrets) expect(kept).not.toContain(secret);
}

describe('PIN login', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('sends a terminal without a POS session to the PIN pad, whatever route it opens', async () => {
    await atLogin({ path: '/tables' });

    expect(codeInput()).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Seguinte' })).toBeDisabled();
  });

  it('logs in with the code, then the hidden PIN, on a numeric pad; keeps the session in memory and opens the tables', async () => {
    const logins = posLoginEndpoint(() => session());
    const rendered = await atLogin();
    const { user, router } = rendered;

    expect(within(pad()).getAllByRole('button').map((key) => key.textContent)).toEqual([
      '1', '2', '3', '4', '5', '6', '7', '8', '9', 'Limpar', '0', 'Apagar',
    ]);
    await tap(user, CODE);
    expect(codeInput()).toHaveValue(CODE);
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));

    expect(screen.getByText(`Empregado ${CODE}`)).toBeInTheDocument();
    expect(pinInput()).toHaveAttribute('type', 'password');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();
    await tap(user, PIN);
    expect(pinInput()).toHaveValue(PIN);
    await user.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
    expect(screen.getByText('Ana Silva')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tables');
    expect(logins).toHaveLength(1);
    expect(logins[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: null, body: { employeeCode: CODE, pin: PIN } });
    expectNoSecretKept(rendered, [PIN, SESSION.posSession]);
  });

  it('every later call carries the terminal token and X-Pos-Session', async () => {
    const { services } = await loggedIn();
    const calls = posSessionEndpoint(() => session({ posSession: undefined }));

    await services.api.GET('/api/v1/auth/pos-session');

    expect(calls[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: SESSION.posSession });
  });

  it('takes the code and the PIN from a keyboard or a badge scanner, Enter moving on', async () => {
    const logins = posLoginEndpoint(() => session());
    const { user } = await atLogin();

    await user.click(codeInput());
    await user.keyboard(`${CODE}{Enter}`);
    await user.keyboard(`${PIN}{Enter}`);

    expect(await screen.findByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
    expect(logins[0]?.body).toEqual({ employeeCode: CODE, pin: PIN });
  });

  it('"Apagar" removes the last digit and "Limpar" empties the field', async () => {
    const { user } = await atLogin();

    await tap(user, CODE);
    await user.click(within(pad()).getByRole('button', { name: 'Apagar' }));
    expect(codeInput()).toHaveValue('104');
    await user.click(within(pad()).getByRole('button', { name: 'Limpar' }));
    expect(codeInput()).toHaveValue('');
  });

  it('refuses a wrong PIN with one generic message, sent once, and empties the PIN', async () => {
    const logins = posLoginEndpoint(() => loginRefused());
    // A renewal would hand out a new token, so a repeated submission would show here (it counts towards the lock).
    const rendered = await atLogin({}, [token('jwt-1'), token('jwt-2')]);

    await logIn(rendered.user);

    expect(await screen.findByRole('alert')).toHaveTextContent('Código ou PIN inválido.');
    expect(pinInput()).toHaveValue('');
    expect(logins).toHaveLength(1);
    expect(rendered.router.state.location.pathname).toBe('/login');
    expectNoSecretKept(rendered, [PIN]);
  });

  it('a locked employee gets the same answer, which says a manager unlocks repeated failures', async () => {
    // The backend answers a locked employee exactly like a wrong PIN (InvalidCredentialsException, D26): no code.
    posLoginEndpoint(() => loginRefused());
    const { user } = await atLogin();

    await logIn(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Código ou PIN inválido. Várias tentativas erradas bloqueiam o acesso: peça a um gerente para o desbloquear.',
    );
  });

  it('"Alterar código" goes back to the code, which is kept', async () => {
    const { user } = await atLogin();
    await tap(user, CODE);
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));

    await user.click(screen.getByRole('button', { name: 'Alterar código' }));

    expect(codeInput()).toHaveValue(CODE);
  });

  it('shows "Sem ligação" when the backend does not answer, keeping what was typed for a retry', async () => {
    const answers = [() => HttpResponse.error(), () => session()];
    const logins = posLoginEndpoint(() => answers.shift()!());
    const { user } = await atLogin();

    await logIn(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — a sessão não foi iniciada. Tente novamente.');
    expect(pinInput()).toHaveValue(PIN);

    await user.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
    expect(logins).toHaveLength(2);
  });

  it('a login that never answers times out as "Sem ligação"', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    posLoginEndpoint(async () => {
      await delay('infinite');
      return session();
    });
    const { user } = await atLogin({ user: { advanceTimers: vi.advanceTimersByTime } });
    await logIn(user);
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();

    await act(() => vi.advanceTimersByTimeAsync(15_000));

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem ligação — a sessão não foi iniciada.');
  });
});

describe('a PIN a manager reset', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  async function mustChange() {
    posLoginEndpoint(() => session({ mustChangePin: true, permissions: [] }));
    const rendered = await atLogin();
    await logIn(rendered.user);
    await screen.findByRole('heading', { name: 'Escolha um novo PIN' });
    return rendered;
  }

  async function choose(user: User, first: string, second: string) {
    await tap(user, first);
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));
    await tap(user, second);
    await user.click(screen.getByRole('button', { name: 'Guardar PIN' }));
  }

  it('asks for a new PIN twice before anything else, then opens the tables', async () => {
    const changes = choosePinEndpoint(() => session({ posSession: undefined }));
    const rendered = await mustChange();
    const { user, router } = rendered;

    expect(screen.queryByRole('button', { name: 'Trocar empregado' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Novo PIN')).toHaveAttribute('type', 'password');
    await tap(user, NEW_PIN);
    await user.click(screen.getByRole('button', { name: 'Seguinte' }));
    expect(screen.getByLabelText('Repita o novo PIN')).toHaveValue('');
    await tap(user, NEW_PIN);
    await user.click(screen.getByRole('button', { name: 'Guardar PIN' }));

    expect(await screen.findByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tables');
    expect(changes).toHaveLength(1);
    expect(changes[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: SESSION.posSession, body: { newPin: NEW_PIN } });
    expectNoSecretKept(rendered, [PIN, NEW_PIN, SESSION.posSession]);
  });

  it('"Sair" logs out and returns to the PIN pad, without changing the PIN', async () => {
    const logouts = posLogoutEndpoint();
    const changes = choosePinEndpoint(() => session());
    const { user, router } = await mustChange();

    await user.click(screen.getByRole('button', { name: 'Sair' }));

    expect(await screen.findByLabelText('Código de empregado')).toHaveValue('');
    expect(router.state.location.pathname).toBe('/login');
    await waitFor(() => expect(logouts).toHaveLength(1));
    expect(logouts[0]?.posSession).toBe(SESSION.posSession);
    expect(changes).toHaveLength(0);
  });

  it('a late PIN-change answer for a session that ended does not change the next employee', async () => {
    let answer!: () => void;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const changes = choosePinEndpoint(async () => {
      await answered;
      return session({ posSession: undefined, mustChangePin: false });
    });
    const rendered = await mustChange();
    const { user, services } = rendered;
    await choose(user, NEW_PIN, NEW_PIN);
    await waitFor(() => expect(changes).toHaveLength(1));
    // Ana's session ends on the server while her answer is late.
    posSessionEndpoint(() => problem(401, 'POS_SESSION_INVALID'));
    await expect(services.api.GET('/api/v1/auth/pos-session')).rejects.toMatchObject({ code: 'POS_SESSION_INVALID' });
    await screen.findByLabelText('Código de empregado');
    posLoginEndpoint(() =>
      session({ name: 'Bruno Costa', posSession: 'pos-session-bruno', mustChangePin: true, permissions: [] }),
    );
    await logIn(user);
    await screen.findByText(/Bruno Costa, o seu PIN foi reposto/);

    answer();
    await waitFor(() => expect(services.session.employee()?.name).toBe('Bruno Costa'));
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(services.session.employee()).toMatchObject({ name: 'Bruno Costa', mustChangePin: true });
    expect(screen.getByText(/Bruno Costa, o seu PIN foi reposto/)).toBeInTheDocument();
  });

  it('needs 4 to 8 digits', async () => {
    const { user } = await mustChange();

    await tap(user, '123');
    expect(screen.getByRole('button', { name: 'Seguinte' })).toBeDisabled();
    await tap(user, '456789');
    expect(screen.getByLabelText('Novo PIN')).toHaveValue('12345678');
  });

  it('two different PINs are refused here, without a request, and it starts over', async () => {
    const changes = choosePinEndpoint(() => session());
    const { user } = await mustChange();

    await choose(user, NEW_PIN, '582048');

    expect(await screen.findByRole('alert')).toHaveTextContent('Os dois PIN não coincidem. Introduza o novo PIN de novo.');
    expect(screen.getByLabelText('Novo PIN')).toHaveValue('');
    expect(changes).toHaveLength(0);
  });

  it('refuses the PIN the manager set (PIN_UNCHANGED)', async () => {
    choosePinEndpoint(() => problem(422, 'PIN_UNCHANGED'));
    const { user } = await mustChange();

    await choose(user, PIN, PIN);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O novo PIN tem de ser diferente do PIN definido pelo gerente.',
    );
    expect(screen.getByLabelText('Novo PIN')).toHaveValue('');
  });

  it('a session that ended meanwhile (POS_SESSION_INVALID) goes back to the PIN pad', async () => {
    choosePinEndpoint(() => problem(401, 'POS_SESSION_INVALID'));
    const { user } = await mustChange();

    await choose(user, NEW_PIN, NEW_PIN);

    expect(await screen.findByLabelText('Código de empregado')).toHaveValue('');
    expect(screen.getByRole('status')).toHaveTextContent('A sessão terminou. Inicie sessão de novo.');
  });
});

describe('leaving the terminal', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('"Trocar empregado" ends the session on the server and returns to an empty PIN pad', async () => {
    const logouts = posLogoutEndpoint();
    const { user, router } = await loggedIn();

    await user.click(screen.getByRole('button', { name: 'Trocar empregado' }));

    expect(await screen.findByLabelText('Código de empregado')).toHaveValue('');
    expect(router.state.location.pathname).toBe('/login');
    await waitFor(() => expect(logouts).toHaveLength(1));
    expect(logouts[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: SESSION.posSession });
  });

  it('logs out after 90 s without input; any input starts the 90 s again', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    const logouts = posLogoutEndpoint();
    const { user } = await loggedIn({ user: { advanceTimers: vi.advanceTimersByTime } });

    await act(() => vi.advanceTimersByTimeAsync(60_000));
    await user.keyboard('{Shift}'); // any key or touch counts as use
    await act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(screen.getByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
    expect(logouts).toHaveLength(0);

    await act(() => vi.advanceTimersByTimeAsync(30_000));

    expect(await screen.findByLabelText('Código de empregado')).toBeInTheDocument();
    await waitFor(() => expect(logouts).toHaveLength(1));
    expect(logouts[0]?.posSession).toBe(SESSION.posSession);
    expect(screen.getByRole('status')).toHaveTextContent('Sessão terminada por inatividade.');
  });

  it('a touch also counts as use', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    posLogoutEndpoint();
    const { user } = await loggedIn({ user: { advanceTimers: vi.advanceTimersByTime } });

    await act(() => vi.advanceTimersByTimeAsync(80_000));
    await user.pointer({ keys: '[TouchA]', target: screen.getByRole('main') });
    await act(() => vi.advanceTimersByTimeAsync(80_000));

    expect(screen.getByRole('button', { name: 'Trocar empregado' })).toBeInTheDocument();
  });

  it('the forced PIN change also ends after 90 s without input', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    const logouts = posLogoutEndpoint();
    posLoginEndpoint(() => session({ mustChangePin: true, permissions: [] }));
    const { user } = await atLogin({ user: { advanceTimers: vi.advanceTimersByTime } });
    await logIn(user);
    await screen.findByRole('heading', { name: 'Escolha um novo PIN' });

    await act(() => vi.advanceTimersByTimeAsync(90_000));

    expect(await screen.findByLabelText('Código de empregado')).toBeInTheDocument();
    await waitFor(() => expect(logouts).toHaveLength(1));
  });

  it('no idle timer runs on the PIN pad itself', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
    await atLogin();

    await act(() => vi.advanceTimersByTimeAsync(120_000));

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  describe('the query cache goes with the session', () => {
    async function withCachedData(options: Parameters<typeof renderPos>[0] = {}) {
      const rendered = await loggedIn(options);
      rendered.queryClient.setQueryData(['pos', 'tables'], [{ id: 'table-1' }]);
      expect(rendered.queryClient.getQueryCache().getAll()).toHaveLength(1);
      return rendered;
    }

    it('on "Trocar empregado"', async () => {
      posLogoutEndpoint();
      const { user, queryClient } = await withCachedData();

      await user.click(screen.getByRole('button', { name: 'Trocar empregado' }));

      await screen.findByLabelText('Código de empregado');
      expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    });

    it('on the idle logout', async () => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true });
      posLogoutEndpoint();
      const { queryClient } = await withCachedData({ user: { advanceTimers: vi.advanceTimersByTime } });

      await act(() => vi.advanceTimersByTimeAsync(90_000));

      await screen.findByLabelText('Código de empregado');
      expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    });

    it('on POS_SESSION_INVALID', async () => {
      const { services, queryClient } = await withCachedData();
      posSessionEndpoint(() => problem(401, 'POS_SESSION_INVALID'));

      await expect(services.api.GET('/api/v1/auth/pos-session')).rejects.toMatchObject({ code: 'POS_SESSION_INVALID' });

      await screen.findByLabelText('Código de empregado');
      expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    });
  });

  it('any call answered POS_SESSION_INVALID returns to the PIN pad', async () => {
    const { services, router } = await loggedIn();
    posSessionEndpoint(() => problem(401, 'POS_SESSION_INVALID'));

    await expect(services.api.GET('/api/v1/auth/pos-session')).rejects.toMatchObject({ code: 'POS_SESSION_INVALID' });

    expect(await screen.findByLabelText('Código de empregado')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('a late POS_SESSION_INVALID for a previous employee does not end the next one', async () => {
    posLogoutEndpoint();
    const { services, user } = await loggedIn();
    let answer!: () => void;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const calls = posSessionEndpoint(async () => {
      await answered;
      return problem(401, 'POS_SESSION_INVALID');
    });
    const late = services.api.GET('/api/v1/auth/pos-session'); // Ana's call, answered after Bruno logs in
    await waitFor(() => expect(calls).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Trocar empregado' }));
    await screen.findByLabelText('Código de empregado');
    posLoginEndpoint(() => session({ name: 'Bruno Costa', posSession: 'pos-session-bruno' }));
    await logIn(user);
    await screen.findByText('Bruno Costa');

    answer();

    await expect(late).rejects.toMatchObject({ code: 'POS_SESSION_INVALID' });
    expect(calls[0]?.posSession).toBe(SESSION.posSession);
    expect(screen.getByText('Bruno Costa')).toBeInTheDocument();
  });
});
