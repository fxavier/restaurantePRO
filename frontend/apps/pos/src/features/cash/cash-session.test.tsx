import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { atCash, json, noOpenSession, OPEN_SESSION, openSessionEndpoint, REGISTER } from '../../test/cash';
import { problem } from '../../test/server';

const sessionCard = () => screen.findByRole('region', { name: /^Caixa Principal/ });

describe('the open cash session', () => {
  it("shows the operator's open session: register, operator, opening time in the store's zone and the float", async () => {
    await atCash();

    const card = await sessionCard();
    expect(within(card).getByText('Sessão aberta')).toBeInTheDocument();
    expect(within(card).getByText('Ana Silva')).toBeInTheDocument();
    // 06:30Z is 08:30 in Africa/Maputo, whatever the terminal's zone.
    expect(within(card).getByText('08:30')).toBeInTheDocument();
    expect(within(card).getByText('19/11/2026')).toBeInTheDocument();
    const float = screen.getByRole('group', { name: 'Fundo de caixa inicial' });
    // The session's currency (MZN), formatted for pt-MZ: never a hard-coded euro.
    expect(float).toHaveTextContent(/1500,00\sMTn/);
  });
});

describe('opening a cash session', () => {
  const openForm = () => screen.findByRole('form', { name: 'Abrir sessão de caixa' });

  it('without an open session, opens one on an active register with the float typed, then shows it', async () => {
    const opened = openSessionEndpoint(json(OPEN_SESSION, 201));
    const { user } = await atCash({ session: [noOpenSession, json(OPEN_SESSION)] });

    const form = await openForm();
    expect(within(form).queryByRole('radio', { name: /Caixa Antiga/ })).not.toBeInTheDocument();
    await user.click(within(form).getByRole('radio', { name: /Caixa Principal/ }));
    await user.type(within(form).getByLabelText('Fundo de caixa inicial'), '1500,00');
    await user.click(within(form).getByRole('button', { name: 'Abrir sessão' }));

    expect(await sessionCard()).toBeInTheDocument();
    expect(opened).toHaveLength(1);
    // The amount crosses the wire as the decimal string typed, never through a float.
    expect(opened[0]!.raw).toBe(`{"registerId":"${REGISTER.main}","openingFloat":"1500.00"}`);
    expect(opened[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('keeps "Abrir sessão" disabled until a register is chosen and the float is a valid amount', async () => {
    const { user } = await atCash({ session: [noOpenSession] });

    const form = await openForm();
    const open = within(form).getByRole('button', { name: 'Abrir sessão' });
    await user.type(within(form).getByLabelText('Fundo de caixa inicial'), '100');
    expect(open).toBeDisabled();
    await user.click(within(form).getByRole('radio', { name: /Caixa Bar/ }));
    expect(open).toBeEnabled();
    await user.type(within(form).getByLabelText('Fundo de caixa inicial'), ',1,2');
    expect(open).toBeDisabled();
  });

  it("explains the server's refusal and keeps the form", async () => {
    openSessionEndpoint(() => problem(409, 'CASH_SESSION_ALREADY_OPEN'));
    const { user } = await atCash({ session: [noOpenSession] });

    const form = await openForm();
    await user.click(within(form).getByRole('radio', { name: /Caixa Principal/ }));
    await user.type(within(form).getByLabelText('Fundo de caixa inicial'), '0');
    await user.click(within(form).getByRole('button', { name: 'Abrir sessão' }));

    expect(await within(form).findByRole('alert')).toHaveTextContent(
      'Esta caixa, ou este operador, já tem uma sessão de caixa aberta.',
    );
  });
});
