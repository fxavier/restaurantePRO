import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { api, baixa, problem } from '../../test/fixtures';
import { diningServer, esplanada, m1, sala } from '../../test/dining-fixtures';
import { sent } from '../../test/organization-fixtures';
import { CSRF_TOKEN, renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const DINING = `/organization/dining?storeId=${baixa.id}`;

describe('rooms and tables', () => {
  it('asks for a store before showing anything', async () => {
    diningServer();

    renderApp('/organization/dining');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver as suas salas e mesas.')).toBeInTheDocument();
  });

  it("shows each area of the store with its tables, their seats and whether one is locked or inactive", async () => {
    diningServer();

    renderApp(DINING);

    const sala = await screen.findByRole('article', { name: 'Sala principal' });
    expect(within(sala).getByText('SALA')).toBeInTheDocument();
    expect(within(sala).getByRole('list', { name: 'Mesas de Sala principal' })).toHaveTextContent('M014 lugares');
    expect(within(sala).getByText('M02').closest('li')).toHaveTextContent('Bloqueada');
    const esplanada = screen.getByRole('article', { name: 'Esplanada' });
    expect(within(esplanada).getByText('E01').closest('li')).toHaveTextContent('Inativa');
  });

  it('creates an area in the store', async () => {
    const state = diningServer();
    const { user } = renderApp(DINING);

    await user.click(await screen.findByRole('button', { name: 'Nova área' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova área' });
    await user.type(within(dialog).getByLabelText('Código'), 'BAR');
    await user.type(within(dialog).getByLabelText('Nome'), 'Balcão');
    await user.click(within(dialog).getByRole('button', { name: 'Criar área' }));

    expect(await screen.findByRole('article', { name: 'Balcão' })).toBeInTheDocument();
    expect(state.writes.map(({ method, path, body, csrf }) => ({ method, path, body, csrf }))).toEqual([
      { method: 'POST', path: '/api/v1/backoffice/areas', body: { storeId: baixa.id, code: 'BAR', name: 'Balcão' }, csrf: CSRF_TOKEN },
    ]);
  });

  it('edits an area at its version, and explains that an area with open tables cannot be deactivated', async () => {
    const state = diningServer();
    server.use(
      http.put(api('/api/v1/backoffice/areas/:id'), async ({ request }) => {
        state.writes.push(await sent(request));
        return problem(422, 'OCCUPIED', 'Unprocessable Content');
      }),
    );
    const { user } = renderApp(DINING);

    const card = await screen.findByRole('article', { name: 'Sala principal' });
    await user.click(within(card).getByRole('button', { name: 'Editar área Sala principal' }));
    const dialog = screen.getByRole('dialog', { name: 'Editar área' });
    await user.click(within(dialog).getByLabelText('Área ativa'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await within(dialog).findByText('A área tem mesas abertas: feche-as antes de a desativar.')).toBeInTheDocument();
    expect(state.writes.map(({ path, body }) => ({ path, body }))).toEqual([
      { path: `/api/v1/backoffice/areas/${sala.id}`, body: { code: 'SALA', name: 'Sala principal', active: false, version: 0 } },
    ]);
  });

  it('adds a table to an area with its seats', async () => {
    const state = diningServer();
    const { user } = renderApp(DINING);

    const card = await screen.findByRole('article', { name: 'Esplanada' });
    await user.click(within(card).getByRole('button', { name: 'Nova mesa em Esplanada' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova mesa' });
    await user.type(within(dialog).getByLabelText('Código'), 'E02');
    await user.type(within(dialog).getByLabelText('Lugares'), '8');
    await user.click(within(dialog).getByRole('button', { name: 'Criar mesa' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await within(screen.getByRole('article', { name: 'Esplanada' })).findByText('E02')).toBeInTheDocument();
    expect(state.writes.map(({ path, body }) => ({ path, body }))).toEqual([
      { path: '/api/v1/backoffice/tables', body: { storeId: baixa.id, areaId: esplanada.id, code: 'E02', seats: 8 } },
    ]);
  });

  it('refuses a table without a positive whole number of seats before sending', async () => {
    const state = diningServer();
    const { user } = renderApp(DINING);

    const card = await screen.findByRole('article', { name: 'Esplanada' });
    await user.click(within(card).getByRole('button', { name: 'Nova mesa em Esplanada' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova mesa' });
    await user.type(within(dialog).getByLabelText('Código'), 'E02');
    await user.type(within(dialog).getByLabelText('Lugares'), '0');
    await user.click(within(dialog).getByRole('button', { name: 'Criar mesa' }));

    expect(await within(dialog).findByText('Indique um número inteiro de lugares, pelo menos 1.')).toBeInTheDocument();
    expect(state.writes).toEqual([]);
  });

  it('moves a table to another area, changes its seats, and shows a taken code on the code field', async () => {
    const state = diningServer();
    let puts = 0;
    server.use(
      http.put(api('/api/v1/backoffice/tables/:id'), async ({ request }) => {
        puts += 1;
        const write = await sent(request);
        state.writes.push(write);
        return puts === 1 ? problem(409, 'DINING_CODE_TAKEN') : HttpResponse.json({ ...m1, ...(write.body as object), version: 1 });
      }),
    );
    const { user } = renderApp(DINING);

    await user.click(await screen.findByRole('button', { name: /^Editar mesa M01/ }));
    const dialog = screen.getByRole('dialog', { name: 'Editar mesa' });
    await user.clear(within(dialog).getByLabelText('Código'));
    await user.type(within(dialog).getByLabelText('Código'), 'M02');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));
    expect(await within(dialog).findByText('Já existe uma área ou mesa com este código nesta loja.')).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText('Código'));
    await user.type(within(dialog).getByLabelText('Código'), 'E09');
    await user.clear(within(dialog).getByLabelText('Lugares'));
    await user.type(within(dialog).getByLabelText('Lugares'), '6');
    await user.selectOptions(within(dialog).getByLabelText('Área'), 'Esplanada');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(state.writes.map(({ body }) => body)[1]).toEqual({ areaId: esplanada.id, code: 'E09', seats: 6, active: true, version: 0 });
  });
});
