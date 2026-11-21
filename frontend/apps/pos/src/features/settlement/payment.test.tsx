import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { ORDER_ID } from '../../test/order';
import { atPayment, billShown } from '../../test/payment-screen';
import { problem } from '../../test/server';
import {
  attempt,
  attemptEndpoint,
  mzn,
  PAID,
  PARTITION_IDS,
  serverSettlement,
  SPLIT_IN_THREE,
} from '../../test/settlement';

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

  it('without an answer, the charge is neither given up nor charged anew: only resent with the same key', async () => {
    const server = serverSettlement();
    const requests = attemptEndpoint(
      () => problem(503, 'BACKEND_UNAVAILABLE'),
      () => {
        server.set(PAID);
        return HttpResponse.json(attempt(), { status: 201 });
      },
    );
    const { user } = await atPayment();
    await billShown();
    await user.click(within(payPanel()).getByRole('button', { name: /^Cobrar 1530,00\sMTn$/ }));

    expect(await within(payPanel()).findByRole('alert')).toHaveTextContent(
      'Sem ligação — o pagamento não foi confirmado. Não cobre de novo: tente novamente.',
    );
    expect(within(payPanel()).getByRole('button', { name: /^Cobrar/ })).toBeDisabled();
    expect(within(payPanel()).queryByRole('button', { name: 'Descartar' })).not.toBeInTheDocument();
    await user.click(within(payPanel()).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(outstanding()).toHaveTextContent('0,00 MTn'));
    expect(requests).toHaveLength(2);
    expect(requests[1]!.idempotencyKey).toBe(requests[0]!.idempotencyKey);
    expect(requests[1]!.raw).toBe(requests[0]!.raw);
  });

  it('a part chosen in the list is what is charged, at its own balance', async () => {
    serverSettlement(SPLIT_IN_THREE);
    const requests = attemptEndpoint(() => HttpResponse.json(attempt({ amount: mzn('510.00'), targetType: 'PARTITION' }), { status: 201 }));
    const { user } = await atPayment();
    await billShown();
    expect(within(payPanel()).getByRole('heading', { name: 'Valor a cobrar — Restante da conta' })).toBeInTheDocument();

    const second = within(screen.getByRole('list', { name: 'Divisões' })).getByRole('button', { name: 'Cobrar Parte 2 de 3' });
    await user.click(second);

    expect(second).toHaveAttribute('aria-pressed', 'true');
    expect(within(payPanel()).getByRole('heading', { name: 'Valor a cobrar — Parte 2 de 3' })).toBeInTheDocument();
    expect(within(payPanel()).getByLabelText('Valor a pagar')).toHaveValue('510.00');
    await user.click(within(payPanel()).getByRole('button', { name: /^Cobrar 510,00\sMTn$/ }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]!.body).toEqual({ orderId: ORDER_ID, partitionId: PARTITION_IDS.second, methodCode: 'CASH', amount: '510.00' });
  });

  it("a part's change is shown under that part only, never under the next one chosen", async () => {
    serverSettlement(SPLIT_IN_THREE);
    attemptEndpoint(() =>
      HttpResponse.json(
        attempt({ amount: mzn('510.00'), tendered: mzn('1000.00'), change: mzn('490.00'), targetType: 'PARTITION', partitionId: PARTITION_IDS.second }),
        { status: 201 },
      ),
    );
    const { user } = await atPayment();
    await billShown();
    const choose = (name: string) => user.click(within(screen.getByRole('list', { name: 'Divisões' })).getByRole('button', { name }));

    await choose('Cobrar Parte 2 de 3');
    await user.type(within(payPanel()).getByLabelText('Valor recebido'), '1000');
    await user.click(within(payPanel()).getByRole('button', { name: /^Cobrar 510,00\sMTn$/ }));
    expect(await within(payPanel()).findByRole('status', { name: 'Último pagamento' })).toHaveTextContent('Troco: 490,00 MTn.');

    await choose('Cobrar Parte 3 de 3');
    expect(within(payPanel()).getByRole('heading', { name: 'Valor a cobrar — Parte 3 de 3' })).toBeInTheDocument();
    expect(within(payPanel()).queryByRole('status', { name: 'Último pagamento' })).not.toBeInTheDocument();
  });
});
