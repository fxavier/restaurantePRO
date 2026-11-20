import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { api, baixa, problem, store, storePage } from '../../test/fixtures';
import { organizationServer, sent } from '../../test/organization-fixtures';
import { CSRF_TOKEN, renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const STORES = '/organization/stores';

describe('stores', () => {
  it('shows each store as a card with its code, timezone, business-day cutoff, currency and state', async () => {
    const closed = { ...store('33333333-0000-4000-8000-000000000003', 'Loja Matola', 'MATOLA'), active: false, businessDayCutoff: '04:30:00' };
    organizationServer({ stores: [baixa, closed] });

    renderApp(STORES);

    const card = await screen.findByRole('article', { name: 'Loja Matola' });
    expect(within(card).getByText('MATOLA')).toBeInTheDocument();
    expect(within(card).getByText('Africa/Maputo')).toBeInTheDocument();
    expect(within(card).getByText('04:30')).toBeInTheDocument();
    expect(within(card).getByText('MZN')).toBeInTheDocument();
    expect(within(card).getByText('Inativa')).toBeInTheDocument();
    expect(within(screen.getByRole('article', { name: 'Loja Baixa' })).getByText('Ativa')).toBeInTheDocument();
  });

  it("leads from a store to its rooms and tables, and to its terminals", async () => {
    organizationServer();
    const { user, currentUrl } = renderApp(STORES);

    const card = await screen.findByRole('article', { name: 'Loja Baixa' });
    expect(within(card).getByRole('link', { name: 'Terminais de Loja Baixa' })).toHaveAttribute(
      'href',
      `/organization/terminals?storeId=${baixa.id}`,
    );
    await user.click(within(card).getByRole('link', { name: 'Salas & Mesas de Loja Baixa' }));

    expect(currentUrl()).toBe(`/organization/dining?storeId=${baixa.id}`);
  });

  it('says there is no connection when the list cannot be read, and reads it again on request', async () => {
    organizationServer();
    let fail = true;
    server.use(
      http.get(api('/api/v1/backoffice/stores'), () =>
        fail ? HttpResponse.error() : HttpResponse.json(storePage([baixa])),
      ),
    );
    const { user } = renderApp(STORES);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Nova loja' })).not.toBeInTheDocument();
    fail = false;
    await user.click(screen.getAllByRole('button', { name: 'Tentar novamente' })[0]!);

    expect(await screen.findByRole('article', { name: 'Loja Baixa' })).toBeInTheDocument();
  });

  it('creates a store with its code, name, timezone and business-day cutoff, then shows it', async () => {
    const state = organizationServer();
    const { user } = renderApp(STORES);

    await user.click(await screen.findByRole('button', { name: 'Nova loja' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova loja' });
    await user.type(within(dialog).getByLabelText('Código'), 'MATOLA');
    await user.type(within(dialog).getByLabelText('Nome'), 'Loja Matola');
    await user.clear(within(dialog).getByLabelText('Fuso horário'));
    await user.type(within(dialog).getByLabelText('Fuso horário'), 'Africa/Maputo');
    await user.type(within(dialog).getByLabelText('Fecho do dia'), '04:30');
    await user.click(within(dialog).getByRole('button', { name: 'Criar loja' }));

    expect(await screen.findByRole('article', { name: 'Loja Matola' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(state.writes).toEqual([
      {
        method: 'POST',
        path: '/api/v1/backoffice/stores',
        body: { code: 'MATOLA', name: 'Loja Matola', timezone: 'Africa/Maputo', businessDayCutoff: '04:30:00' },
        idempotencyKey: null,
        csrf: CSRF_TOKEN,
      },
    ]);
  });

  it('shows a taken code on the code field and keeps the dialog open', async () => {
    organizationServer();
    server.use(http.post(api('/api/v1/backoffice/stores'), () => problem(409, 'STORE_CODE_TAKEN')));
    const { user } = renderApp(STORES);

    await user.click(await screen.findByRole('button', { name: 'Nova loja' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova loja' });
    await user.type(within(dialog).getByLabelText('Código'), 'BAIXA');
    await user.type(within(dialog).getByLabelText('Nome'), 'Outra Baixa');
    await user.click(within(dialog).getByRole('button', { name: 'Criar loja' }));

    expect(await within(dialog).findByText('Já existe uma loja com este código.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Código')).toHaveAttribute('aria-invalid', 'true');
  });

  it('says the outcome is unknown when the server cannot be reached, and reads the list again', async () => {
    const state = organizationServer();
    server.use(http.post(api('/api/v1/backoffice/stores'), () => HttpResponse.error()));
    const { user } = renderApp(STORES);

    await user.click(await screen.findByRole('button', { name: 'Nova loja' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova loja' });
    await user.type(within(dialog).getByLabelText('Código'), 'MATOLA');
    await user.type(within(dialog).getByLabelText('Nome'), 'Loja Matola');
    const reads = state.storeReads;
    await user.click(within(dialog).getByRole('button', { name: 'Criar loja' }));

    expect(await within(dialog).findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await waitFor(() => expect(state.storeReads).toBeGreaterThan(reads));
  });

  it('edits a store at the version it was read, and can deactivate it', async () => {
    const state = organizationServer();
    const { user } = renderApp(STORES);

    const card = await screen.findByRole('article', { name: 'Loja Baixa' });
    await user.click(within(card).getByRole('button', { name: 'Editar Loja Baixa' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar loja' });
    expect(within(dialog).getByLabelText('Fecho do dia')).toHaveValue('05:00');
    await user.clear(within(dialog).getByLabelText('Nome'));
    await user.type(within(dialog).getByLabelText('Nome'), 'Loja da Baixa');
    await user.click(within(dialog).getByLabelText('Loja ativa'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    const renamed = await screen.findByRole('article', { name: 'Loja da Baixa' });
    expect(within(renamed).getByText('Inativa')).toBeInTheDocument();
    expect(state.writes.map(({ method, path, body }) => ({ method, path, body }))).toEqual([
      {
        method: 'PUT',
        path: `/api/v1/backoffice/stores/${baixa.id}`,
        body: {
          code: 'BAIXA',
          name: 'Loja da Baixa',
          timezone: 'Africa/Maputo',
          businessDayCutoff: '05:00:00',
          active: false,
          version: 0,
        },
      },
    ]);
  });

  it('on a version conflict loads what the other person saved and lets the user save again at that version', async () => {
    const state = organizationServer();
    const theirs = { ...baixa, name: 'Baixa (renovada)', version: 1 };
    let puts = 0;
    server.use(
      http.get(api(`/api/v1/backoffice/stores/${baixa.id}`), () => HttpResponse.json(theirs)),
      http.put(api('/api/v1/backoffice/stores/:id'), async ({ request }) => {
        puts += 1;
        state.writes.push(await sent(request));
        return puts === 1 ? problem(409, 'VERSION_CONFLICT') : HttpResponse.json({ ...theirs, version: 2 });
      }),
    );
    const { user } = renderApp(STORES);

    const card = await screen.findByRole('article', { name: 'Loja Baixa' });
    await user.click(within(card).getByRole('button', { name: 'Editar Loja Baixa' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar loja' });
    await user.clear(within(dialog).getByLabelText('Nome'));
    await user.type(within(dialog).getByLabelText('Nome'), 'A minha Baixa');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByText(/Foi alterada por outra pessoa/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Nome')).toHaveValue('Baixa (renovada)');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.writes.map(({ body }) => (body as { version: number }).version)).toEqual([0, 1]);
  });
});
