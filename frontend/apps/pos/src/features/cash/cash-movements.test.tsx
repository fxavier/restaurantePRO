import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atCash, json, movement, movementEndpoint, SESSION_ID } from '../../test/cash';
import { problem } from '../../test/server';

const lastMovement = () => screen.getByRole('status', { name: 'Último movimento' });
const sessionCard = () => screen.findByRole('region', { name: /^Caixa Principal/ });

async function startMovement(user: Awaited<ReturnType<typeof atCash>>['user'], action: string) {
  await user.click(within(await sessionCard()).getByRole('button', { name: action }));
  return screen.findByRole('dialog', { name: action });
}

describe('cash movements', () => {
  it.each([
    ['Entrada de numerário', 'CASH_IN', 'Entrada de numerário registada'],
    ['Saída de numerário', 'CASH_OUT', 'Saída de numerário registada'],
  ])('"%s" records the amount and reason in the session, and says what the server recorded', async (action, type, done) => {
    const recorded = movementEndpoint(json(movement({ type, amount: { amount: '200.00', currency: 'MZN' } }), 201));
    const { user } = await atCash();

    const dialog = await startMovement(user, action);
    // The session's currency labels the amount: never a hard-coded one.
    await user.type(within(dialog).getByLabelText('Valor (MZN)'), '200,00');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Trocos do banco');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));

    await waitFor(() => expect(lastMovement()).toHaveTextContent(new RegExp(`${done}: 200,00\\sMTn`)));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(recorded).toHaveLength(1);
    expect(recorded[0]!.raw).toBe(`{"sessionId":"${SESSION_ID}","type":"${type}","amount":"200.00","reason":"Trocos do banco"}`);
    expect(recorded[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(recorded.approvers[0]).toEqual({ code: null, pin: null });
  });

  it('an expense needs another employee to approve it with their code and PIN, sent as headers only', async () => {
    const recorded = movementEndpoint(json(movement({ type: 'EXPENSE', amount: { amount: '45.00', currency: 'MZN' } }), 201));
    const { user } = await atCash();

    const dialog = await startMovement(user, 'Despesa autorizada');
    await user.type(within(dialog).getByLabelText('Valor (MZN)'), '45');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Fornecedor de hortícolas');
    const register = within(dialog).getByRole('button', { name: 'Registar' });
    expect(register).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Código do aprovador'), '2001');
    await user.type(within(dialog).getByLabelText('PIN do aprovador'), '482913');
    await user.click(register);

    await waitFor(() => expect(lastMovement()).toHaveTextContent(/Despesa registada: 45,00\sMTn/));
    expect(recorded.approvers[0]).toEqual({ code: '2001', pin: '482913' });
    expect(recorded[0]!.raw).not.toContain('482913');
  });

  it('a refused approval keeps the dialog open and says why', async () => {
    movementEndpoint(() => problem(403, 'FORBIDDEN'));
    const { user } = await atCash();

    const dialog = await startMovement(user, 'Despesa autorizada');
    await user.type(within(dialog).getByLabelText('Valor (MZN)'), '45');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Fornecedor');
    await user.type(within(dialog).getByLabelText('Código do aprovador'), '2001');
    await user.type(within(dialog).getByLabelText('PIN do aprovador'), '000000');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Aprovação recusada: confirme o código e o PIN do aprovador, que tem de poder autorizar despesas.',
    );
  });

  it('with no answer, "Tentar novamente" resends the same movement with the same key', async () => {
    const recorded = movementEndpoint(
      () => HttpResponse.error(),
      json(movement({ type: 'CASH_IN', amount: { amount: '10.00', currency: 'MZN' } }), 201),
    );
    const { user } = await atCash();

    const dialog = await startMovement(user, 'Entrada de numerário');
    await user.type(within(dialog).getByLabelText('Valor (MZN)'), '10');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Fundo');
    await user.click(within(dialog).getByRole('button', { name: 'Registar' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Sem ligação — não foi possível confirmar o movimento.',
    );
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(recorded).toHaveLength(2));
    expect(recorded[1]!.idempotencyKey).toBe(recorded[0]!.idempotencyKey);
    await waitFor(() => expect(lastMovement()).toHaveTextContent(/Entrada de numerário registada: 10,00\sMTn/));
  });
});
