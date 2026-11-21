import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, problem, stepUpRequired } from '../../test/fixtures';
import { customersServer, customerUrl, lusitania, lusitaniaCredit, mzn } from '../../test/customers-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const CREDIT = `/api/v1/backoffice/customers/${lusitania.id}/credit`;

async function openCredit(url = customerUrl(lusitania.id)) {
  const app = renderApp(url);
  const detail = await screen.findByRole('region', { name: lusitania.name });
  await app.user.click(within(detail).getByRole('tab', { name: 'Crédito' }));
  return { ...app, panel: within(detail).getByRole('tabpanel', { name: 'Crédito' }) };
}

describe('credit', () => {
  it('shows the limit, the debt, the reserved and the available credit as the server computed them', async () => {
    customersServer();

    const { panel } = await openCredit();

    const figures = await within(panel).findByRole('list', { name: 'Conta de crédito' });
    expect(within(figures).getByText('Limite').parentElement).toHaveTextContent('1000,00 MTn');
    expect(within(figures).getByText('Dívida').parentElement).toHaveTextContent('450,20 MTn');
    expect(within(figures).getByText('Reservado').parentElement).toHaveTextContent('0,00 MTn');
    expect(within(figures).getByText('Disponível').parentElement).toHaveTextContent('549,80 MTn');
    expect(within(panel).getByText('Crédito ativo')).toBeInTheDocument();
  });

  it('lists the statement with each movement and the running balance the server sent', async () => {
    customersServer();

    const { panel } = await openCredit();

    const statement = await within(panel).findByRole('table', { name: 'Extrato de conta corrente' });
    const rows = within(statement).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(/02\/11\/2026.*Fatura FT 2026\/1792.*Débito.*490,00 MTn.*490,00 MTn/);
    expect(rows[1]).toHaveTextContent(/10\/11\/2026.*Recebimento por transferência.*Crédito.*300,00 MTn.*190,00 MTn/);
    expect(within(panel).getByText('Saldo final').parentElement).toHaveTextContent('450,20 MTn');
  });

  it('says the customer has no credit account instead of failing', async () => {
    customersServer();
    server.use(http.get(api(CREDIT), () => problem(422, 'CREDIT_ACCOUNT_MISSING')));

    const { panel } = await openCredit();

    expect(await within(panel).findByText('Este cliente ainda não tem conta de crédito.')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Definir limite' })).toBeInTheDocument();
  });

  it('changes the limit with a reason, under one key, and shows the account the server answers', async () => {
    const { requests } = customersServer();
    const raised = { ...lusitaniaCredit, limit: mzn('1500.50'), available: mzn('1050.30') };
    let changed = false;
    server.use(
      http.post(api(`${CREDIT}-limit-changes`), () => {
        changed = true;
        return HttpResponse.json(raised);
      }),
      http.get(api(CREDIT), () => HttpResponse.json(changed ? raised : lusitaniaCredit)),
    );
    const { panel, user } = await openCredit();

    await user.click(await within(panel).findByRole('button', { name: 'Alterar limite' }));
    const dialog = await screen.findByRole('dialog', { name: 'Alterar limite de crédito' });
    await user.type(within(dialog).getByLabelText('Novo limite'), '1500,50');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Aumento acordado');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar limite' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const figures = within(panel).getByRole('list', { name: 'Conta de crédito' });
    await waitFor(() => expect(within(figures).getByText('Limite').parentElement).toHaveTextContent('1500,50 MTn'));
    const sent = requests.filter((r) => r.path.endsWith('/credit-limit-changes'));
    expect(sent).toEqual([
      expect.objectContaining({
        method: 'POST',
        body: { newLimit: '1500.50', reason: 'Aumento acordado' },
        idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
        csrf: 'csrf-token-1',
      }),
    ]);
  });

  it('checks the form before sending anything', async () => {
    const { requests } = customersServer();
    const { panel, user } = await openCredit();

    await user.click(await within(panel).findByRole('button', { name: 'Alterar limite' }));
    const dialog = await screen.findByRole('dialog', { name: 'Alterar limite de crédito' });
    await user.type(within(dialog).getByLabelText('Novo limite'), '15,999');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar limite' }));

    expect(await within(dialog).findByText('Indique um valor como 1500,00.')).toBeInTheDocument();
    expect(within(dialog).getByText('Indique o motivo.')).toBeInTheDocument();
    expect(requests.filter((r) => r.method === 'POST')).toEqual([]);
  });

  it('without an answer, offers to send the same change again under the same key', async () => {
    const { requests } = customersServer();
    let attempts = 0;
    server.use(
      http.post(api(`${CREDIT}-limit-changes`), () =>
        ++attempts === 1 ? HttpResponse.error() : HttpResponse.json(lusitaniaCredit),
      ),
    );
    const { panel, user } = await openCredit();

    await user.click(await within(panel).findByRole('button', { name: 'Alterar limite' }));
    const dialog = await screen.findByRole('dialog', { name: 'Alterar limite de crédito' });
    await user.type(within(dialog).getByLabelText('Novo limite'), '2000');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Revisão anual');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar limite' }));
    expect(await within(dialog).findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const keys = requests.filter((r) => r.path.endsWith('/credit-limit-changes')).map((r) => r.idempotencyKey);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it('sends the user to confirm their identity and back to this customer when the server asks for it', async () => {
    customersServer();
    server.use(http.post(api(`${CREDIT}-block`), () => stepUpRequired()));
    const { panel, user, leave } = await openCredit();

    await user.click(await within(panel).findByRole('button', { name: 'Bloquear crédito' }));
    const dialog = await screen.findByRole('dialog', { name: 'Bloquear crédito' });
    await user.type(within(dialog).getByLabelText('Motivo'), 'Dívida em atraso');
    await user.click(within(dialog).getByRole('button', { name: 'Bloquear' }));
    await user.click(await within(dialog).findByRole('button', { name: 'Confirmar identidade' }));

    expect(leave).toHaveBeenCalledWith(
      `/bff/step-up?${new URLSearchParams({ acr_values: '2', max_age: '300', return: customerUrl(lusitania.id) })}`,
    );
  });

  it('blocks with a reason and unblocks, each shown from the server answer', async () => {
    const { requests } = customersServer();
    let blocked = false;
    server.use(
      http.post(api(`${CREDIT}-block`), () => {
        blocked = true;
        return HttpResponse.json({ ...lusitaniaCredit, blocked });
      }),
      http.post(api(`${CREDIT}-unblock`), () => {
        blocked = false;
        return HttpResponse.json({ ...lusitaniaCredit, blocked });
      }),
      http.get(api(CREDIT), () => HttpResponse.json({ ...lusitaniaCredit, blocked })),
    );
    const { panel, user } = await openCredit();

    await user.click(await within(panel).findByRole('button', { name: 'Bloquear crédito' }));
    const blockDialog = await screen.findByRole('dialog', { name: 'Bloquear crédito' });
    await user.type(within(blockDialog).getByLabelText('Motivo'), 'Dívida em atraso');
    await user.click(within(blockDialog).getByRole('button', { name: 'Bloquear' }));
    expect(await within(panel).findByText('Crédito bloqueado')).toBeInTheDocument();

    await user.click(within(panel).getByRole('button', { name: 'Desbloquear crédito' }));
    const unblockDialog = await screen.findByRole('dialog', { name: 'Desbloquear crédito' });
    await user.click(within(unblockDialog).getByRole('button', { name: 'Desbloquear' }));
    expect(await within(panel).findByText('Crédito ativo')).toBeInTheDocument();

    expect(requests.filter((r) => r.method === 'POST').map((r) => [r.path.split('/').pop(), r.body])).toEqual([
      ['credit-block', { reason: 'Dívida em atraso' }],
      ['credit-unblock', { reason: '' }],
    ]);
  });

  it('shows no credit command to a user who may only view customers', async () => {
    customersServer({ session: admin });

    const { panel } = await openCredit();

    await within(panel).findByRole('list', { name: 'Conta de crédito' });
    expect(within(panel).queryByRole('button', { name: 'Alterar limite' })).not.toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Bloquear crédito' })).not.toBeInTheDocument();
  });
});
