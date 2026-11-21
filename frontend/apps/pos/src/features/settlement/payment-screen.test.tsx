import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { ORDER_ID } from '../../test/order';
import { atPayment, billShown } from '../../test/payment-screen';
import { notificationsEndpoint, problem, SESSION } from '../../test/server';
import { CLOSED, PAID, serverSettlement, SETTLEMENT, settlementEndpoint, SPLIT_IN_THREE } from '../../test/settlement';

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

  it('"Sem ligação" disables every command: no split, no charge, no document, no close', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    serverSettlement({ ...SPLIT_IN_THREE, financialState: 'SETTLED' });
    const { user } = await atPayment();
    await billShown();

    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Sem ligação'));
    expect(screen.getAllByText('Sem ligação — nenhuma operação é confirmada').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('tab', { name: /Partes iguais/ }));
    expect(screen.getByRole('button', { name: /^Dividir em/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cobrar Parte 1 de 3' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancelar Parte 1 de 3' })).toBeDisabled();
    expect(within(screen.getByRole('region', { name: 'Pagamento' })).getByRole('button', { name: /^Cobrar/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Emitir documento' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Fechar conta' })).toBeDisabled();
  });

  it('a closed bill says so and takes no command', async () => {
    serverSettlement(CLOSED);
    await atPayment();
    await billShown();

    expect(await screen.findByRole('alert')).toHaveTextContent('Esta conta está fechada.');
    expect(within(screen.getByRole('region', { name: 'Pagamento' })).getByRole('button', { name: /^Cobrar/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Fechar conta' })).toBeDisabled();
    // A copy of its document is still possible.
    expect(screen.getByRole('button', { name: '2ª via' })).toBeEnabled();
  });

  it('an ORDER change on the stream reads the bill again; its payload is never applied', async () => {
    const stream = notificationsEndpoint();
    const server = serverSettlement();
    await atPayment();
    await billShown();
    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Ligado'));

    server.set(PAID);
    stream.streams.at(-1)!.change('ORDER', ORDER_ID);

    await waitFor(() =>
      expect(within(screen.getByRole('group', { name: 'Resumo da conta' })).getByText('Saldo pendente').nextSibling).toHaveTextContent(
        '0,00 MTn',
      ),
    );
  });

  it('a waiter without the cashier\'s permissions sees the bill, but neither splits, charges nor closes it', async () => {
    serverSettlement(SPLIT_IN_THREE);
    await atPayment({ permissions: [`PERM_ORDER_CREATE@${SESSION.storeId}`] });
    await billShown();

    expect(screen.getByRole('group', { name: 'Resumo da conta' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Divisões' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Divisão da conta' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Cancelar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Cobrar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Fechar conta' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Emitir documento' })).not.toBeInTheDocument();
    expect(screen.getByText('Sem permissão para dividir ou cobrar esta conta.')).toBeInTheDocument();
  });

  it('an unknown order is not found, with no retry', async () => {
    settlementEndpoint(() => problem(404, 'NOT_FOUND'));
    await atPayment();

    expect(await screen.findByRole('alert')).toHaveTextContent('Pedido não encontrado.');
    expect(screen.queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
  });
});
