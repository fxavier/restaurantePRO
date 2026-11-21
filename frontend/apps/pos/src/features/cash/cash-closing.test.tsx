import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  atCash,
  CLOSING,
  CLOSING_ID,
  createClosingEndpoint,
  DAY_ID,
  json,
  justifyClosingEndpoint,
  noOpenSession,
  REGISTER,
  submitClosingEndpoint,
} from '../../test/cash';
import { problem } from '../../test/server';

const closingCard = () => screen.findByRole('region', { name: 'Fecho da caixa' });

const JUSTIFIED = {
  ...CLOSING,
  version: 1,
  lines: CLOSING.lines!.map((line) => (line.methodCode === 'CASH' ? { ...line, justification: 'Troco a mais' } : line)),
};

async function prepare(user: Awaited<ReturnType<typeof atCash>>['user']) {
  const card = await closingCard();
  await user.click(within(card).getByRole('radio', { name: /Caixa Principal/ }));
  await user.click(within(card).getByRole('button', { name: 'Preparar fecho' }));
  return card;
}

describe("the register's closing", () => {
  it("prepares the closing of a register for the day and shows the server's expected, counted and difference", async () => {
    const created = createClosingEndpoint(json(CLOSING));
    const { user } = await atCash({ session: [noOpenSession] });

    const card = await prepare(user);

    const table = await within(card).findByRole('table', { name: 'Diferenças por meio de pagamento' });
    const cash = within(table).getByRole('row', { name: /Numerário/ });
    expect(cash).toHaveTextContent(/4875,50\sMTn/);
    expect(cash).toHaveTextContent(/4850,50\sMTn/);
    expect(cash).toHaveTextContent(/-25,00\sMTn/);
    expect(within(table).getByRole('row', { name: /CARD/ })).toHaveTextContent('Sem diferença');
    expect(created[0]!.body).toEqual({ cashDayId: DAY_ID, registerId: REGISTER.main });
    expect(created[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a difference must be justified before the closing is submitted for approval', async () => {
    createClosingEndpoint(json(CLOSING));
    const justified = justifyClosingEndpoint(json(JUSTIFIED));
    const submitted = submitClosingEndpoint(json({ ...JUSTIFIED, status: 'SUBMITTED', version: 2 }));
    const { user } = await atCash({ session: [noOpenSession] });

    const card = await prepare(user);
    const submit = await within(card).findByRole('button', { name: 'Submeter fecho' });
    expect(submit).toBeDisabled();
    await user.type(within(card).getByLabelText('Justificação: Numerário'), 'Troco a mais');
    await user.click(within(card).getByRole('button', { name: 'Guardar justificações' }));
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);

    expect(await within(card).findByText('Submetido')).toBeInTheDocument();
    expect(within(card).getByText('O fecho foi submetido para aprovação do gerente.')).toBeInTheDocument();
    expect(justified[0]!.body).toEqual({ justifications: [{ methodCode: 'CASH', text: 'Troco a mais' }], version: 0 });
    expect(justified[0]!.url).toContain(`/closings/${CLOSING_ID}`);
    expect(submitted).toHaveLength(1);
    expect(submitted[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a closing already live for the register (a lost draft, or one submitted) is named, with what to do', async () => {
    createClosingEndpoint(() => problem(409, 'ILLEGAL_CASH_STATE'));
    const { user } = await atCash({ session: [noOpenSession] });

    const card = await prepare(user);

    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'Esta caixa já tem um fecho em curso neste dia (em rascunho ou submetido), que este terminal não consegue reabrir. ' +
        'Se foi submetido, aguarde a decisão do gerente; se ficou em rascunho, peça ajuda ao gerente.',
    );
  });

  it("shows the latest command's failure, not an older one's", async () => {
    // The server already holds the justification, so the closing can be submitted even after a failed save.
    createClosingEndpoint(json(JUSTIFIED));
    justifyClosingEndpoint(() => problem(409, 'VERSION_CONFLICT'));
    submitClosingEndpoint(() => problem(422, 'JUSTIFICATION_REQUIRED'));
    const { user } = await atCash({ session: [noOpenSession] });

    const card = await prepare(user);
    await user.click(await within(card).findByRole('button', { name: 'Guardar justificações' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent('Foi alterado noutro terminal.');
    await user.click(within(card).getByRole('button', { name: 'Submeter fecho' }));

    await waitFor(() =>
      expect(within(card).getByRole('alert')).toHaveTextContent('Justifique cada diferença antes de submeter o fecho.'),
    );
  });

  it('while a submit with no answer waits for its retry, the justifications cannot be saved', async () => {
    createClosingEndpoint(json(JUSTIFIED));
    submitClosingEndpoint(() => HttpResponse.error());
    const { user } = await atCash({ session: [noOpenSession] });

    const card = await prepare(user);
    await user.click(await within(card).findByRole('button', { name: 'Submeter fecho' }));

    expect(await within(card).findByRole('button', { name: 'Tentar novamente' })).toBeEnabled();
    expect(within(card).getByRole('button', { name: 'Guardar justificações' })).toBeDisabled();
  });

  it('says why the server refuses to prepare it', async () => {
    createClosingEndpoint(() => problem(422, 'SESSION_STILL_OPEN'));
    const { user } = await atCash({ session: [noOpenSession] });

    const card = await prepare(user);

    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'Esta caixa ainda tem uma sessão aberta. Feche a sessão antes de preparar o fecho.',
    );
  });
});
