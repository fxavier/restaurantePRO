import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, baixa } from '../../test/fixtures';
import { customersAdmin, customersServer, customerUrl, lusitania, lusitaniaLoyalty } from '../../test/customers-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const ACCOUNT = `/api/v1/backoffice/loyalty/accounts/${lusitania.id}`;

async function openLoyalty(url = customerUrl(lusitania.id)) {
  const app = renderApp(url);
  const detail = await screen.findByRole('region', { name: lusitania.name });
  await app.user.click(within(detail).getByRole('tab', { name: 'Fidelização' }));
  return { ...app, panel: within(detail).getByRole('tabpanel', { name: 'Fidelização' }) };
}

describe('loyalty', () => {
  it('shows the points, the state and the movements as the server sent them', async () => {
    customersServer();

    const { panel } = await openLoyalty();

    expect(await within(panel).findByText('320 pontos')).toBeInTheDocument();
    expect(within(panel).getByText('Conta ativa')).toBeInTheDocument();
    const movements = await within(panel).findByRole('table', { name: 'Movimentos de pontos' });
    const rows = within(movements).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent(/02\/11\/2026.*Acumulação.*\+300.*300/);
    expect(rows[1]).toHaveTextContent(/12\/11\/2026.*Ajuste manual.*Compensação por atraso.*\+20.*320/);
  });

  it('says when the customer has not joined, which only the POS can do', async () => {
    customersServer();
    server.use(http.get(api(ACCOUNT), () => HttpResponse.json({ customerId: lusitania.id, enrolled: false, points: 0 })));

    const { panel } = await openLoyalty();

    expect(await within(panel).findByText('O cliente não aderiu à fidelização. A adesão faz-se no POS.')).toBeInTheDocument();
    expect(within(panel).queryByRole('table', { name: 'Movimentos de pontos' })).not.toBeInTheDocument();
  });

  it('suspends with a reason and resumes, showing the state the server answers', async () => {
    const { requests } = customersServer();
    let status = 'ACTIVE';
    server.use(
      http.post(api(`${ACCOUNT}/suspend`), () => {
        status = 'SUSPENDED';
        return HttpResponse.json({ customerId: lusitania.id, status, points: 320 });
      }),
      http.post(api(`${ACCOUNT}/resume`), () => {
        status = 'ACTIVE';
        return HttpResponse.json({ customerId: lusitania.id, status, points: 320 });
      }),
      http.get(api(ACCOUNT), () => HttpResponse.json({ ...lusitaniaLoyalty, status })),
    );
    const { panel, user } = await openLoyalty();

    await user.click(await within(panel).findByRole('button', { name: 'Suspender' }));
    const suspend = await screen.findByRole('dialog', { name: 'Suspender a conta de fidelização' });
    await user.type(within(suspend).getByLabelText('Motivo'), 'Uso indevido');
    await user.click(within(suspend).getByRole('button', { name: 'Suspender conta' }));
    expect(await within(panel).findByText('Conta suspensa')).toBeInTheDocument();

    await user.click(within(panel).getByRole('button', { name: 'Reativar' }));
    const resume = await screen.findByRole('dialog', { name: 'Reativar a conta de fidelização' });
    await user.type(within(resume).getByLabelText('Motivo'), 'Esclarecido');
    await user.click(within(resume).getByRole('button', { name: 'Reativar conta' }));
    expect(await within(panel).findByText('Conta ativa')).toBeInTheDocument();

    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts.map((r) => [r.path.split('/').pop(), r.body])).toEqual([
      ['suspend', { reason: 'Uso indevido' }],
      ['resume', { reason: 'Esclarecido' }],
    ]);
    expect(posts.every((r) => /^[0-9a-f-]{36}$/.test(r.idempotencyKey ?? ''))).toBe(true);
  });

  it('adjusts points in the name of the store picked in the top bar', async () => {
    const { requests } = customersServer();
    server.use(
      http.post(api(`${ACCOUNT}/adjustments`), () =>
        HttpResponse.json({ id: 'l0000000-0000-4000-8000-000000000003', kind: 'ADJUSTMENT', points: -20 }),
      ),
    );
    const { panel, user } = await openLoyalty(`${customerUrl(lusitania.id)}&storeId=${baixa.id}`);

    await user.click(await within(panel).findByRole('button', { name: 'Ajustar pontos' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar pontos' });
    await user.type(within(dialog).getByLabelText('Pontos'), '-20');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Correção de lançamento');
    await user.click(within(dialog).getByRole('button', { name: 'Ajustar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const post = requests.find((r) => r.path.endsWith('/adjustments'));
    expect(post?.body).toEqual({ points: -20, reason: 'Correção de lançamento', storeId: baixa.id });
  });

  it('refuses a zero adjustment before sending it', async () => {
    const { requests } = customersServer();
    const { panel, user } = await openLoyalty(`${customerUrl(lusitania.id)}&storeId=${baixa.id}`);

    await user.click(await within(panel).findByRole('button', { name: 'Ajustar pontos' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ajustar pontos' });
    await user.type(within(dialog).getByLabelText('Pontos'), '0');
    await user.type(within(dialog).getByLabelText('Motivo'), 'Teste');
    await user.click(within(dialog).getByRole('button', { name: 'Ajustar' }));

    expect(await within(dialog).findByText('Indique um número inteiro de pontos, diferente de zero.')).toBeInTheDocument();
    expect(requests.filter((r) => r.method === 'POST')).toEqual([]);
  });

  it('asks for a store before an adjustment', async () => {
    customersServer();

    const { panel } = await openLoyalty();

    expect(await within(panel).findByRole('button', { name: 'Ajustar pontos' })).toBeDisabled();
    expect(within(panel).getByText('Escolha uma loja na barra superior para ajustar pontos.')).toBeInTheDocument();
  });

  it('hides the loyalty tab from a user who may not see it, and its commands from one who may only see it', async () => {
    customersServer({ session: admin });
    renderApp(customerUrl(lusitania.id));
    const detail = await screen.findByRole('region', { name: lusitania.name });
    expect(within(detail).queryByRole('tab', { name: 'Fidelização' })).not.toBeInTheDocument();
  });

  it('shows the loyalty without commands to a user who may only see it', async () => {
    customersServer({ session: { ...customersAdmin, permissions: [...admin.permissions, 'PERM_LOYALTY_VIEW'] } });

    const { panel } = await openLoyalty();

    expect(await within(panel).findByText('320 pontos')).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Suspender' })).not.toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Ajustar pontos' })).not.toBeInTheDocument();
  });
});
