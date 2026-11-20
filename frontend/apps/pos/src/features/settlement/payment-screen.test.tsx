import { screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atPayment, billShown } from '../../test/payment-screen';
import { problem } from '../../test/server';
import { serverSettlement, SETTLEMENT, settlementEndpoint } from '../../test/settlement';

describe('Conta & Pagamento', () => {
  it("shows the bill's total, what is paid and what is outstanding, as the server has them", async () => {
    serverSettlement();
    await atPayment();
    await billShown();

    const totals = screen.getByRole('group', { name: 'Resumo da conta' });
    expect(within(totals).getByText('Total da conta').nextSibling).toHaveTextContent('1530,00 MTn');
    expect(within(totals).getByText('Pago').nextSibling).toHaveTextContent('0,00 MTn');
    expect(within(totals).getByText('Saldo pendente').nextSibling).toHaveTextContent('1530,00 MTn');
  });

  it('says the bill is loading until the server answers', async () => {
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    settlementEndpoint(async () => {
      await released;
      return HttpResponse.json(SETTLEMENT);
    });
    await atPayment();

    expect(await screen.findByRole('status', { name: 'A carregar a conta…' })).toBeInTheDocument();
    release();
    await screen.findByRole('group', { name: 'Resumo da conta' });
    expect(screen.queryByRole('status', { name: 'A carregar a conta…' })).not.toBeInTheDocument();
  });

  it.each([
    [503, 'BACKEND_UNAVAILABLE', 'Sem ligação — não foi possível carregar a conta.'],
    [500, 'INTERNAL', 'Não foi possível carregar a conta. Erro inesperado (INTERNAL).'],
  ])('a %i answer says so and offers to read the bill again', async (status, code, text) => {
    const reads = settlementEndpoint(() => problem(status, code), () => HttpResponse.json(SETTLEMENT));
    const { user } = await atPayment();

    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    const before = reads.length;
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));

    await screen.findByRole('group', { name: 'Resumo da conta' });
    expect(reads.length).toBeGreaterThan(before);
  });

  it('an unknown order is not found, with no retry', async () => {
    settlementEndpoint(() => problem(404, 'NOT_FOUND'));
    await atPayment();

    expect(await screen.findByRole('alert')).toHaveTextContent('Pedido não encontrado.');
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
  });
});
