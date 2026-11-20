import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  atCash,
  CLOSED_SESSION,
  closeSessionEndpoint,
  drawerKickEndpoint,
  json,
  noOpenSession,
  OPEN_SESSION,
} from '../../test/cash';
import { problem } from '../../test/server';

const blindCount = () => screen.findByRole('form', { name: 'Contagem cega' });

describe('the cash drawer', () => {
  it('"Abrir gaveta" asks the printer to open the drawer and says the request was sent', async () => {
    const kicks = drawerKickEndpoint(json({ id: 'job', status: 'QUEUED', sourceType: 'DRAWER_KICK' }, 201));
    const { user } = await atCash();

    await user.click(within(await screen.findByRole('region', { name: /^Caixa Principal/ })).getByRole('button', { name: 'Abrir gaveta' }));

    await waitFor(() =>
      expect(screen.getByRole('status', { name: 'Último movimento' })).toHaveTextContent('Pedido de abertura da gaveta enviado.'),
    );
    expect(kicks).toHaveLength(1);
    expect(kicks[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('the blind count and the session close', () => {
  it('asks for a count per method the session saw, never showing what is expected', async () => {
    await atCash();

    const form = await blindCount();
    expect(within(form).getByLabelText(/^Numerário/)).toBeInTheDocument();
    expect(within(form).getByLabelText(/^CARD/)).toBeInTheDocument();
    // P23: the expected amounts (4875,50 and 3210,40) are not shown before the count.
    expect(document.body).not.toHaveTextContent(/4875,50|3210,40/);
  });

  it('closes the session with the counts typed, after a confirmation, and then offers a new session', async () => {
    const closed = closeSessionEndpoint(json(CLOSED_SESSION));
    const { user } = await atCash({ session: [json(OPEN_SESSION), noOpenSession] });

    const form = await blindCount();
    const close = within(form).getByRole('button', { name: 'Fechar sessão' });
    await user.type(within(form).getByLabelText(/^Numerário/), '4850,50');
    expect(close).toBeDisabled();
    await user.type(within(form).getByLabelText(/^CARD/), '3210.40');
    await user.click(close);
    const confirm = await screen.findByRole('alertdialog', { name: 'Fechar a sessão de caixa?' });
    await user.click(within(confirm).getByRole('button', { name: 'Confirmar fecho' }));

    expect(await screen.findByRole('form', { name: 'Abrir sessão de caixa' })).toBeInTheDocument();
    expect(closed).toHaveLength(1);
    expect(closed[0]!.raw).toBe(
      '{"counts":[{"methodCode":"CASH","amount":"4850.50"},{"methodCode":"CARD","amount":"3210.40"}],"version":2}',
    );
    expect(closed[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a payment in doubt keeps the session open and says why', async () => {
    closeSessionEndpoint(() => problem(409, 'PAYMENT_IN_DOUBT'));
    const { user } = await atCash();

    const form = await blindCount();
    await user.type(within(form).getByLabelText(/^Numerário/), '0');
    await user.type(within(form).getByLabelText(/^CARD/), '0');
    await user.click(within(form).getByRole('button', { name: 'Fechar sessão' }));
    await user.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Confirmar fecho' }),
    );

    expect(await within(form).findByRole('alert')).toHaveTextContent(
      'Há pagamentos pendentes de reconciliação. Resolva-os antes de fechar.',
    );
  });

  it('with no answer, "Tentar novamente" resends the same count with the same key', async () => {
    const closed = closeSessionEndpoint(() => HttpResponse.error(), json(CLOSED_SESSION));
    const { user } = await atCash({ session: [json(OPEN_SESSION), json(OPEN_SESSION), noOpenSession] });

    const form = await blindCount();
    await user.type(within(form).getByLabelText(/^Numerário/), '10');
    await user.type(within(form).getByLabelText(/^CARD/), '0');
    await user.click(within(form).getByRole('button', { name: 'Fechar sessão' }));
    await user.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Confirmar fecho' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Sem ligação — não foi possível confirmar o fecho da sessão.');
    expect(within(form).getByLabelText(/^Numerário/)).toHaveAttribute('readonly');
    await user.click(within(form).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(closed).toHaveLength(2));
    expect(closed[1]!.idempotencyKey).toBe(closed[0]!.idempotencyKey);
  });
});
