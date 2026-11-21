import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atPayment, billShown } from '../../test/payment-screen';
import { problem } from '../../test/server';
import {
  cancelPartitionEndpoint,
  equalPartsEndpoint,
  ITEMS_PARTITION,
  LINE_IDS,
  mzn,
  NULL,
  part,
  PARTITION_IDS,
  partitionEndpoint,
  serverSettlement,
  SETTLEMENT,
  SPLIT_IN_THREE,
} from '../../test/settlement';

const splitPanel = () => screen.getByRole('region', { name: 'Divisão da conta' });
const parts = () => screen.getByRole('list', { name: 'Divisões' });

describe('splitting the bill', () => {
  it('in equal parts: one intent for the number of people chosen, then the parts as the server cut them', async () => {
    const server = serverSettlement();
    const requests = equalPartsEndpoint(() => {
      server.set(SPLIT_IN_THREE);
      return HttpResponse.json([part(1), part(2), part(3)], { status: 201 });
    });
    const { user } = await atPayment();
    await billShown();

    await user.click(within(splitPanel()).getByRole('tab', { name: /Partes iguais/ }));
    // The order's guests (3) to start with; one more, one less.
    expect(within(splitPanel()).getByRole('group', { name: 'Pessoas' })).toHaveTextContent('3');
    await user.click(within(splitPanel()).getByRole('button', { name: 'Mais uma pessoa' }));
    await user.click(within(splitPanel()).getByRole('button', { name: 'Menos uma pessoa' }));
    await user.click(within(splitPanel()).getByRole('button', { name: 'Dividir em 3 partes' }));

    await waitFor(() => expect(within(parts()).getAllByRole('listitem')).toHaveLength(3));
    expect(within(parts()).getAllByRole('listitem')[0]).toHaveTextContent('Parte 1 de 3');
    expect(within(parts()).getAllByRole('listitem')[0]).toHaveTextContent('510,00 MTn');
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0]!.body).toEqual({ orderVersion: 4, parts: 3 });
  });

  it('by items: the quantities chosen of each line still undivided, as one part', async () => {
    const server = serverSettlement({
      ...SETTLEMENT,
      // One beer is already in another part: one is left.
      coverage: SETTLEMENT.coverage!.map((line) => (line.lineId === LINE_IDS.beer ? { ...line, partitioned: '1.000' } : line)),
    });
    const requests = partitionEndpoint(() => {
      server.set({ ...SETTLEMENT, orderVersion: 5, partitions: [ITEMS_PARTITION] });
      return HttpResponse.json(ITEMS_PARTITION, { status: 201 });
    });
    const { user } = await atPayment();
    await billShown();

    await user.click(within(splitPanel()).getByRole('tab', { name: 'Por artigos' }));
    const lines = within(splitPanel()).getByRole('list', { name: 'Artigos por dividir' });
    expect(within(lines).getAllByRole('listitem').map((line) => line.textContent)).toEqual([
      expect.stringContaining('Bitoque0 de 3'),
      expect.stringContaining('Cerveja 2M0 de 1'),
    ]);
    const create = within(splitPanel()).getByRole('button', { name: 'Criar divisão' });
    expect(create).toBeDisabled();
    await user.click(within(lines).getByRole('button', { name: 'Mais Bitoque' }));
    await user.click(within(lines).getByRole('button', { name: 'Mais Bitoque' }));
    expect(within(lines).getByRole('button', { name: 'Mais Cerveja 2M' })).toBeEnabled();
    await user.click(within(lines).getByRole('button', { name: 'Mais Cerveja 2M' }));
    expect(within(lines).getByRole('button', { name: 'Mais Cerveja 2M' })).toBeDisabled();
    await user.click(within(lines).getByRole('button', { name: 'Menos Cerveja 2M' }));
    await user.click(create);

    await waitFor(() => expect(within(parts()).getAllByRole('listitem')).toHaveLength(1));
    expect(within(parts()).getByRole('listitem')).toHaveTextContent('2 × Bitoque');
    expect(within(parts()).getByRole('listitem')).toHaveTextContent('900,00 MTn');
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0]!.body).toEqual({ orderVersion: 4, kind: 'ITEMS', lines: [{ lineId: LINE_IDS.steak, quantity: '2' }] });
  });

  it('without an answer, keeps the split unconfirmed and resends it with the same key', async () => {
    const server = serverSettlement();
    const requests = equalPartsEndpoint(
      () => problem(503, 'BACKEND_UNAVAILABLE'),
      () => {
        server.set(SPLIT_IN_THREE);
        return HttpResponse.json([part(1), part(2), part(3)], { status: 201 });
      },
    );
    const { user } = await atPayment();
    await billShown();
    await user.click(within(splitPanel()).getByRole('tab', { name: /Partes iguais/ }));
    await user.click(within(splitPanel()).getByRole('button', { name: 'Dividir em 3 partes' }));

    expect(await within(splitPanel()).findByRole('alert')).toHaveTextContent('Sem ligação — a divisão não foi confirmada.');
    expect(within(splitPanel()).getByRole('button', { name: 'Dividir em 3 partes' })).toBeDisabled();
    await user.click(within(splitPanel()).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(within(parts()).getAllByRole('listitem')).toHaveLength(3));
    expect(requests).toHaveLength(2);
    expect(requests[1]!.idempotencyKey).toBe(requests[0]!.idempotencyKey);
  });

  it('a part is cancelled at its version, and its quantities go back to the bill', async () => {
    const server = serverSettlement(SPLIT_IN_THREE);
    const requests = cancelPartitionEndpoint(PARTITION_IDS.third, () => {
      server.set({ ...SPLIT_IN_THREE, partitions: [part(1), part(2), part(3, { status: 'CANCELLED' })] });
      return HttpResponse.json(part(3, { status: 'CANCELLED', version: 1 }));
    });
    const { user } = await atPayment();
    await billShown();

    const third = within(parts()).getAllByRole('listitem')[2]!;
    await user.click(within(third).getByRole('button', { name: 'Cancelar Parte 3 de 3' }));

    await waitFor(() => expect(within(parts()).getAllByRole('listitem')).toHaveLength(2));
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0]!.body).toEqual({ version: 0, reason: 'Divisão refeita no ponto de venda' });
  });

  it('an untouched part as the server sends it (no document: `documentId: null`) is offered to be cancelled', async () => {
    serverSettlement({ ...SPLIT_IN_THREE, partitions: [part(1, { documentId: NULL }), part(2), part(3)] });
    await atPayment();
    await billShown();

    const first = within(parts()).getAllByRole('listitem')[0]!;
    expect(within(first).getByRole('button', { name: 'Cancelar Parte 1 de 3' })).toBeEnabled();
  });

  it('a part already paid is not offered to be cancelled', async () => {
    serverSettlement({ ...SPLIT_IN_THREE, partitions: [part(1, { paid: mzn('510.00'), balance: mzn('0.00'), paymentStatus: 'PAID', financialState: 'SETTLED' }), part(2), part(3)] });
    await atPayment();
    await billShown();

    const first = within(parts()).getAllByRole('listitem')[0]!;
    expect(first).toHaveTextContent('Pago');
    expect(within(first).queryByRole('button', { name: /^Cancelar/ })).not.toBeInTheDocument();
    expect(within(first).queryByRole('button', { name: /^Cobrar/ })).not.toBeInTheDocument();
  });

  it.each([
    [409, 'VERSION_CONFLICT', 'A conta foi alterada noutro terminal. Reveja-a e tente de novo.'],
    [422, 'NOTHING_TO_SPLIT', 'Não há valor suficiente para dividir em tantas partes.'],
  ])('a %i %s refusal says why and reads the bill again', async (status, code, text) => {
    const server = serverSettlement();
    equalPartsEndpoint(() => problem(status, code));
    const { user } = await atPayment();
    await billShown();
    await user.click(within(splitPanel()).getByRole('tab', { name: /Partes iguais/ }));
    const before = server.reads.length;

    await user.click(within(splitPanel()).getByRole('button', { name: 'Dividir em 3 partes' }));

    expect(await within(splitPanel()).findByRole('alert')).toHaveTextContent(`A divisão não foi feita. ${text}`);
    await waitFor(() => expect(server.reads.length).toBeGreaterThan(before));
    expect(within(splitPanel()).getByRole('button', { name: 'Dividir em 3 partes' })).toBeEnabled();
  });
});
