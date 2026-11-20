import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { ORDER_ID } from '../../test/order';
import { atPayment, billShown } from '../../test/payment-screen';
import { attempt, attemptEndpoint, PAID, serverSettlement } from '../../test/settlement';

const payPanel = () => screen.getByRole('region', { name: 'Pagamento' });
const outstanding = () => within(screen.getByRole('group', { name: 'Resumo da conta' })).getByText('Saldo pendente').nextSibling;

describe('charging the bill', () => {
  it('in cash: the amount due as the server sent it, what was handed over, and the change the server answered', async () => {
    const server = serverSettlement();
    const requests = attemptEndpoint(() => {
      server.set(PAID);
      return HttpResponse.json(attempt(), { status: 201 });
    });
    const { user } = await atPayment();
    await billShown();

    expect(within(payPanel()).getByRole('heading', { name: 'Valor a cobrar — Conta total' })).toBeInTheDocument();
    expect(within(payPanel()).getByRole('radio', { name: /Numerário/ })).toBeChecked();
    expect(within(payPanel()).getByLabelText('Valor a pagar')).toHaveValue('1530.00');
    await user.type(within(payPanel()).getByLabelText('Valor recebido'), '2000');
    // Intl separates the currency with a no-break space.
    await user.click(within(payPanel()).getByRole('button', { name: /^Cobrar 1530,00\sMTn$/ }));

    expect(await within(payPanel()).findByRole('status', { name: 'Último pagamento' })).toHaveTextContent(
      'Pago 1530,00 MTn em numerário. Troco: 470,00 MTn.',
    );
    await waitFor(() => expect(outstanding()).toHaveTextContent('0,00 MTn'));
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    // The amounts as typed and as the server sent them, never a sum made here.
    expect(requests[0]!.raw).toBe(JSON.stringify({ orderId: ORDER_ID, methodCode: 'CASH', amount: '1530.00', tendered: '2000' }));
  });
});
