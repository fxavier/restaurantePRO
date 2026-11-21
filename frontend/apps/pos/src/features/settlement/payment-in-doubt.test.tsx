import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atPayment, billShown } from '../../test/payment-screen';
import {
  attempt,
  ATTEMPT_ID,
  cancelAttemptEndpoint,
  confirmAttemptEndpoint,
  mzn,
  NULL,
  PAID,
  resolveAttemptEndpoint,
  serverSettlement,
  SETTLEMENT,
} from '../../test/settlement';

const payPanel = () => screen.getByRole('region', { name: 'Pagamento' });
const inFlight = () => screen.getByRole('region', { name: 'Pagamentos em curso' });

// On the whole order: the server sends `partitionId: null`, never leaves it out.
const UNKNOWN = attempt({
  status: 'UNKNOWN',
  methodCode: 'MPESA',
  methodKind: 'INTEGRATED',
  amount: mzn('1530.00'),
  partitionId: NULL,
  tendered: NULL,
  change: NULL,
});
const PENDING = attempt({
  status: 'PENDING',
  methodCode: 'CARD',
  methodKind: 'EXTERNAL_TERMINAL',
  amount: mzn('1530.00'),
  partitionId: NULL,
  tendered: NULL,
  change: NULL,
});

describe('a payment in doubt', () => {
  it('is pending reconciliation, never failed, and the same bill cannot be charged again meanwhile', async () => {
    serverSettlement({ ...SETTLEMENT, openAttempts: [UNKNOWN] });
    await atPayment();
    await billShown();

    const attemptRow = within(inFlight()).getByRole('listitem');
    expect(attemptRow).toHaveTextContent('MPESA');
    expect(attemptRow).toHaveTextContent('1530,00 MTn');
    expect(attemptRow).toHaveTextContent('Por confirmar — reconciliação pendente');
    expect(attemptRow).not.toHaveTextContent(/falh/i);
    expect(within(payPanel()).getByRole('button', { name: /^Cobrar/ })).toBeDisabled();
    expect(within(payPanel()).getByText('Há um pagamento em curso nesta conta. Resolva-o antes de cobrar de novo.')).toBeInTheDocument();
  });

  it('"Consultar o estado" asks the provider once, with its own key, and shows what the server then has', async () => {
    const server = serverSettlement({ ...SETTLEMENT, openAttempts: [UNKNOWN] });
    const requests = resolveAttemptEndpoint(ATTEMPT_ID, () => {
      server.set(PAID);
      return HttpResponse.json({ ...UNKNOWN, status: 'SUCCEEDED' });
    });
    const { user } = await atPayment();
    await billShown();

    await user.click(within(inFlight()).getByRole('button', { name: 'Consultar o estado' }));

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Pagamentos em curso' })).not.toBeInTheDocument());
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    // No outcome: the provider is asked; reclassifying needs an approver, which this screen does not offer.
    expect(requests[0]!.body).toEqual({});
  });

  it('a card terminal\'s payment waits for the operator: confirmed with the authorization code', async () => {
    const server = serverSettlement({ ...SETTLEMENT, openAttempts: [PENDING] });
    const requests = confirmAttemptEndpoint(ATTEMPT_ID, () => {
      server.set(PAID);
      return HttpResponse.json({ ...PENDING, status: 'SUCCEEDED', authorizationCode: 'A1B2' });
    });
    const { user } = await atPayment();
    await billShown();

    const row = within(inFlight()).getByRole('listitem');
    expect(row).toHaveTextContent('A aguardar o terminal de pagamento');
    await user.type(within(row).getByLabelText('Código de autorização'), 'A1B2');
    await user.click(within(row).getByRole('button', { name: 'Confirmar pagamento' }));

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Pagamentos em curso' })).not.toBeInTheDocument());
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0]!.body).toEqual({ authorizationCode: 'A1B2' });
  });

  it('a card terminal\'s payment that did not go through is cancelled, with a reason', async () => {
    const server = serverSettlement({ ...SETTLEMENT, openAttempts: [PENDING] });
    const requests = cancelAttemptEndpoint(ATTEMPT_ID, () => {
      server.set(SETTLEMENT);
      return HttpResponse.json({ ...PENDING, status: 'CANCELLED' });
    });
    const { user } = await atPayment();
    await billShown();

    await user.click(within(inFlight()).getByRole('button', { name: 'Cancelar pagamento' }));

    await waitFor(() => expect(within(payPanel()).getByRole('button', { name: /^Cobrar/ })).toBeEnabled());
    expect(requests).toHaveLength(1);
    expect(requests[0]!.body).toEqual({ reason: 'Não aprovado no terminal' });
  });
});
