import { screen, waitFor, within } from '@testing-library/react';
import { delay } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  ENROLMENT,
  enrolled,
  enrolmentEndpoint,
  problem,
  token,
  tokenEndpoint,
} from '../../test/server';
import { renderPos } from '../../test/render-pos';

/** 8 characters of selector, 16 of secret: the backend's shape (not a real code). */
const CODE = 'K7M2P9QXABCDEFGH23456789';

const codeInput = () => screen.getByLabelText('Código de registo');
const submit = () => screen.getByRole('button', { name: 'Registar terminal' });

async function typeCode(user: Awaited<ReturnType<typeof renderPos>>['user'], code = CODE) {
  await user.click(codeInput());
  await user.keyboard(code);
}

describe('enrolment', () => {
  it('opens on a terminal with no device key, with an empty code and the action disabled', async () => {
    await renderPos({ path: '/' });

    expect(await screen.findByRole('heading', { level: 2, name: 'Registar este terminal' })).toBeInTheDocument();
    expect(codeInput()).toHaveValue('');
    expect(submit()).toBeDisabled();
    const keypad = screen.getByRole('group', { name: 'Teclado do código' });
    expect(within(keypad).getAllByRole('button')).toHaveLength(33); // 32 characters and "Apagar"
  });

  it('takes the code from the on-screen keypad, shown in groups of four, and "Apagar" removes the last character', async () => {
    const { user } = await renderPos();
    await screen.findByLabelText('Código de registo');

    for (const character of CODE) await user.click(screen.getByRole('button', { name: character }));

    expect(codeInput()).toHaveValue('K7M2 P9QX ABCD EFGH 2345 6789');
    expect(submit()).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Apagar' }));

    expect(codeInput()).toHaveValue('K7M2 P9QX ABCD EFGH 2345 678');
    expect(submit()).toBeDisabled();
  });

  it('enrols: one non-extractable key, the code and the public JWK sent, the answer stored, then the PIN screen', async () => {
    const bodies = enrolmentEndpoint(() => enrolled());
    const forms = tokenEndpoint(token('jwt-1'));
    const { user, devices } = await renderPos();
    await screen.findByLabelText('Código de registo');

    // A keyboard or scanner: lower case and dashes are fine, Enter submits.
    await typeCode(user, 'k7m2-p9qx-abcd-efgh-2345-6789{Enter}');

    expect(await screen.findByText('Terminal registado.')).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
    expect(bodies[0]?.code).toBe(CODE);
    expect(Object.keys(bodies[0]!.publicJwk).sort()).toEqual(['crv', 'kty', 'x', 'y']);
    const stored = await devices.load();
    expect(stored?.enrolment).toEqual(ENROLMENT);
    expect(stored?.keyPair.privateKey.extractable).toBe(false);
    await waitFor(() => expect(forms).toHaveLength(1));
    expect(forms[0]?.get('client_id')).toBe(ENROLMENT.clientId);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('refuses a wrong, used or expired code with one message, and keeps the terminal unenrolled', async () => {
    enrolmentEndpoint(() => problem(400, 'ENROLMENT_CODE_INVALID'));
    const { user, devices } = await renderPos();
    await screen.findByLabelText('Código de registo');

    await typeCode(user);
    await user.click(submit());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Código inválido, já utilizado ou expirado. Confirme o código ou peça um novo ao administrador.',
    );
    expect(codeInput()).toHaveValue('K7M2 P9QX ABCD EFGH 2345 6789');
    expect((await devices.load())?.enrolment).toBeUndefined();
  });

  it('shows "Demasiadas tentativas" with the Retry-After seconds when throttled', async () => {
    enrolmentEndpoint(() => problem(429, 'ENROLMENT_THROTTLED', { 'Retry-After': '30' }));
    const { user } = await renderPos();
    await screen.findByLabelText('Código de registo');

    await typeCode(user);
    await user.click(submit());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Demasiadas tentativas. Tente novamente dentro de 30 s.',
    );
  });

  it('falls back to "um minuto", the backend window, when a 429 has no Retry-After', async () => {
    enrolmentEndpoint(() => problem(429, 'ENROLMENT_THROTTLED'));
    const { user } = await renderPos();
    await screen.findByLabelText('Código de registo');

    await typeCode(user);
    await user.click(submit());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Demasiadas tentativas. Tente novamente dentro de um minuto.',
    );
  });

  it('shows "Sem ligação" when the backend is unreachable, and retries with the same key', async () => {
    const answers = [() => Response.error(), () => enrolled()];
    const bodies = enrolmentEndpoint(() => answers.shift()!());
    tokenEndpoint(token('jwt-1'));
    const { user } = await renderPos();
    await screen.findByLabelText('Código de registo');

    await typeCode(user);
    await user.click(submit());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Sem ligação — o terminal não foi registado. Tente novamente.',
    );

    await user.click(submit());

    expect(await screen.findByText('Terminal registado.')).toBeInTheDocument();
    expect(bodies).toHaveLength(2);
    expect(bodies[1]?.publicJwk).toEqual(bodies[0]?.publicJwk);
  });

  it('reuses a key whose enrolment did not finish (the backend replays only for that key)', async () => {
    const bodies = enrolmentEndpoint(() => enrolled());
    tokenEndpoint(token('jwt-1'));
    const { user, devices } = await renderPos({ device: 'key-only' });
    const before = await crypto.subtle.exportKey('jwk', (await devices.load())!.keyPair.publicKey);
    await screen.findByLabelText('Código de registo');

    await typeCode(user);
    await user.click(submit());

    await screen.findByText('Terminal registado.');
    expect(bodies[0]?.publicJwk).toMatchObject({ x: before.x, y: before.y });
  });

  it('disables the action and the keypad while the enrolment is in flight', async () => {
    enrolmentEndpoint(async () => {
      await delay('infinite');
      return enrolled();
    });
    const { user } = await renderPos();
    await screen.findByLabelText('Código de registo');

    await typeCode(user);
    await user.click(submit());

    await waitFor(() => expect(submit()).toBeDisabled());
    expect(submit()).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('button', { name: 'Apagar' })).toBeDisabled();
    expect(codeInput()).toHaveAttribute('readonly');
  });

  it('sends an enrolled terminal on to the PIN screen instead', async () => {
    tokenEndpoint(token('jwt-1'));
    await renderPos({ path: '/enrol', device: 'enrolled' });

    expect(await screen.findByText('Terminal registado.')).toBeInTheDocument();
  });
});
