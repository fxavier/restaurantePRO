import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atPayment, billShown } from '../../test/payment-screen';
import {
  DOCUMENT,
  DOCUMENT_ID,
  ISSUED,
  ITEMS_PARTITION,
  mzn,
  partitionDocumentEndpoint,
  reprintEndpoint,
  serverSettlement,
  SETTLEMENT,
} from '../../test/settlement';
import { id } from '../../test/table-map';

const parts = () => screen.getByRole('list', { name: 'Divisões' });
const PAID_PART = { ...ITEMS_PARTITION, paid: mzn('900.00'), balance: mzn('0.00'), paymentStatus: 'PAID', financialState: 'SETTLED' };
const PART_DOCUMENT_ID = id(1201);

describe('documents', () => {
  it("a paid part by items gets its own document, for the buyer chosen, while the table goes on", async () => {
    const server = serverSettlement({ ...SETTLEMENT, partitions: [PAID_PART] });
    const requests = partitionDocumentEndpoint(PAID_PART.id!, () => {
      server.set({ ...SETTLEMENT, partitions: [{ ...PAID_PART, invoiced: true, documentId: PART_DOCUMENT_ID }] });
      return HttpResponse.json({ ...DOCUMENT, id: PART_DOCUMENT_ID, number: 43 }, { status: 201 });
    });
    const { user } = await atPayment();
    await billShown();

    await user.click(screen.getByRole('radio', { name: 'Com NIF' }));
    await user.type(screen.getByLabelText('NIF'), '400123456');
    await user.click(within(parts()).getByRole('button', { name: 'Emitir documento de Por artigos' }));

    expect(await within(parts()).findByRole('status', { name: 'Documento de Por artigos' })).toHaveTextContent('Documento A/43 emitido.');
    expect(within(parts()).queryByRole('button', { name: 'Emitir documento de Por artigos' })).not.toBeInTheDocument();
    expect(requests).toHaveLength(1);
    expect(requests[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(requests[0]!.body).toEqual({ buyer: { taxId: '400123456' } });
  });

  it('an issued document is printed again as a copy ("2ª via"), never a new document', async () => {
    serverSettlement({ ...ISSUED, partitions: [{ ...PAID_PART, invoiced: true, documentId: PART_DOCUMENT_ID }] });
    const orderCopies = reprintEndpoint(DOCUMENT_ID, () =>
      HttpResponse.json({ documentId: DOCUMENT_ID, copyNo: 1, requestedAt: '2026-11-18T11:07:00Z' }, { status: 201 }),
    );
    const partCopies = reprintEndpoint(PART_DOCUMENT_ID, () =>
      HttpResponse.json({ documentId: PART_DOCUMENT_ID, copyNo: 2, requestedAt: '2026-11-18T11:07:00Z' }, { status: 201 }),
    );
    const { user } = await atPayment();
    await billShown();

    await user.click(within(parts()).getByRole('button', { name: '2ª via de Por artigos' }));
    expect(await within(parts()).findByRole('status', { name: 'Documento de Por artigos' })).toHaveTextContent('2ª via n.º 2 enviada para impressão.');
    await user.click(within(screen.getByRole('region', { name: 'Documento e fecho' })).getByRole('button', { name: '2ª via' }));

    await waitFor(() => expect(orderCopies).toHaveLength(1));
    expect(partCopies).toHaveLength(1);
    expect(partCopies[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(orderCopies[0]!.idempotencyKey).not.toBe(partCopies[0]!.idempotencyKey);
  });
});
