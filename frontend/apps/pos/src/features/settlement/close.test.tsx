import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atPayment, billShown } from '../../test/payment-screen';
import {
  CLOSED,
  closeEndpoint,
  DOCUMENT,
  ISSUED,
  orderDocumentEndpoint,
  PAID,
  serverSettlement,
  SETTLEMENT,
} from '../../test/settlement';

const closing = () => screen.getByRole('region', { name: 'Documento e fecho' });

describe('issuing the document and closing the bill', () => {
  it('waits until the server says the bill is paid', async () => {
    serverSettlement(SETTLEMENT);
    await atPayment();
    await billShown();

    expect(within(closing()).getByRole('button', { name: 'Emitir documento' })).toBeDisabled();
    expect(within(closing()).getByRole('button', { name: 'Fechar conta' })).toBeDisabled();
    expect(within(closing()).getByText('A conta ainda não está paga.')).toBeInTheDocument();
  });

  it('issues the document for the buyer typed, then closes the order at its version and returns to the tables', async () => {
    const server = serverSettlement(PAID);
    const documents = orderDocumentEndpoint(() => {
      server.set(ISSUED);
      return HttpResponse.json(DOCUMENT, { status: 201 });
    });
    const closes = closeEndpoint(() => {
      server.set(CLOSED);
      return HttpResponse.json(CLOSED);
    });
    const { user, router } = await atPayment();
    await billShown();

    await user.click(within(closing()).getByRole('radio', { name: 'Com NIF' }));
    await user.type(within(closing()).getByLabelText('NIF'), '400123456');
    await user.type(within(closing()).getByLabelText('Nome'), 'Empresa Lda');
    await user.click(within(closing()).getByRole('button', { name: 'Emitir documento' }));

    expect(await within(closing()).findByRole('status', { name: 'Documento' })).toHaveTextContent('Documento A/42 emitido.');
    expect(within(closing()).getByRole('button', { name: 'Emitir documento' })).toBeDisabled();
    await user.click(within(closing()).getByRole('button', { name: 'Fechar conta' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/tables'));
    expect(documents).toHaveLength(1);
    expect(documents[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(documents[0]!.body).toEqual({ buyer: { taxId: '400123456', name: 'Empresa Lda' } });
    expect(closes).toHaveLength(1);
    expect(closes[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(closes[0]!.body).toEqual({ orderVersion: 5 });
  });

  it('a final consumer\'s document names no buyer', async () => {
    const server = serverSettlement(PAID);
    const documents = orderDocumentEndpoint(() => {
      server.set(ISSUED);
      return HttpResponse.json(DOCUMENT, { status: 201 });
    });
    const { user } = await atPayment();
    await billShown();

    expect(within(closing()).getByRole('radio', { name: 'Consumidor final' })).toBeChecked();
    await user.click(within(closing()).getByRole('button', { name: 'Emitir documento' }));

    await waitFor(() => expect(documents).toHaveLength(1));
    expect(documents[0]!.body).toEqual({});
  });

  it('a close refused because the bill changed says so and stays', async () => {
    serverSettlement(ISSUED);
    closeEndpoint(() =>
      HttpResponse.json(
        { type: 'about:blank', title: 'Unprocessable', status: 422, detail: 'x', code: 'NOT_SETTLED' },
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    );
    const { user, router } = await atPayment();
    await billShown();

    await user.click(within(closing()).getByRole('button', { name: 'Fechar conta' }));

    expect(await within(closing()).findByRole('alert')).toHaveTextContent(
      'A conta não foi fechada. A conta ainda não está paga na totalidade.',
    );
    expect(router.state.location.pathname).toMatch(/\/payment$/);
  });
});
