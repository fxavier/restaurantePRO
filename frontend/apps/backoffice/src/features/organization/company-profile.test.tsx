import { screen, within } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { admin, api, problem, stepUpRequired } from '../../test/fixtures';
import { organizationServer } from '../../test/organization-fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const STORES = '/organization/stores';

describe('company profile', () => {
  it("shows the company's legal name, tax number, country and currency", async () => {
    organizationServer();

    renderApp(STORES);

    const card = await screen.findByRole('region', { name: 'Restaurante A, Lda' });
    expect(within(card).getByText('400123456')).toBeInTheDocument();
    expect(within(card).getByText('MZ')).toBeInTheDocument();
    expect(within(card).getByText('MZN')).toBeInTheDocument();
  });

  it('is not asked for without the permission to manage the company', async () => {
    let reads = 0;
    organizationServer({ session: admin });
    server.use(http.get(api('/api/v1/backoffice/company-profile'), () => void (reads += 1)));

    renderApp(STORES);

    expect(await screen.findByRole('article', { name: 'Loja Baixa' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /Restaurante A/ })).not.toBeInTheDocument();
    expect(reads).toBe(0);
  });

  it('asks the user to confirm their identity before showing it (a step-up permission), and comes back here', async () => {
    organizationServer();
    server.use(http.get(api('/api/v1/backoffice/company-profile'), () => stepUpRequired(300)));
    const { user, leave } = renderApp(STORES);

    expect(await screen.findByText('Para ver o perfil da empresa, confirme a sua identidade.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirmar identidade' }));

    expect(leave).toHaveBeenCalledWith('/bff/step-up?acr_values=2&max_age=300&return=%2Forganization%2Fstores');
  });

  it('edits the profile at the version it was read', async () => {
    const state = organizationServer();
    const { user } = renderApp(STORES);

    const card = await screen.findByRole('region', { name: 'Restaurante A, Lda' });
    await user.click(within(card).getByRole('button', { name: 'Editar perfil' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar perfil da empresa' });
    await user.clear(within(dialog).getByLabelText('Nome legal'));
    await user.type(within(dialog).getByLabelText('Nome legal'), 'Restaurante A, SA');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByRole('region', { name: 'Restaurante A, SA' })).toBeInTheDocument();
    expect(state.writes.map(({ method, path, body }) => ({ method, path, body }))).toEqual([
      {
        method: 'PUT',
        path: '/api/v1/backoffice/company-profile',
        body: { name: 'Restaurante A, SA', taxId: '400123456', country: 'MZ', currency: 'MZN', version: 3 },
      },
    ]);
  });

  it('explains that the currency cannot change once a store exists', async () => {
    organizationServer();
    server.use(
      http.put(api('/api/v1/backoffice/company-profile'), () =>
        problem(422, undefined, 'Unprocessable Content', {
          detail: 'The company currency cannot change after the first store exists.',
        }),
      ),
    );
    const { user } = renderApp(STORES);

    const card = await screen.findByRole('region', { name: 'Restaurante A, Lda' });
    await user.click(within(card).getByRole('button', { name: 'Editar perfil' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar perfil da empresa' });
    await user.clear(within(dialog).getByLabelText('Moeda'));
    await user.type(within(dialog).getByLabelText('Moeda'), 'usd');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(
      await within(dialog).findByText('A moeda da empresa não pode mudar depois de criada a primeira loja.'),
    ).toBeInTheDocument();
  });

  it('a step-up asked by the save offers to confirm the identity, then come back to this page', async () => {
    organizationServer();
    server.use(http.put(api('/api/v1/backoffice/company-profile'), () => stepUpRequired(120)));
    const { user, leave } = renderApp(STORES);

    const card = await screen.findByRole('region', { name: 'Restaurante A, Lda' });
    await user.click(within(card).getByRole('button', { name: 'Editar perfil' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar perfil da empresa' });
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
    await user.click(await within(dialog).findByRole('button', { name: 'Confirmar identidade' }));

    expect(leave).toHaveBeenCalledWith('/bff/step-up?acr_values=2&max_age=120&return=%2Forganization%2Fstores');
  });
});
