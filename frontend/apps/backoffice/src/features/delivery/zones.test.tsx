import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { centro, dish, feeFar, feeNear, polana, zone, zonesServer } from '../../test/delivery-fixtures';
import { api, baixa, problem } from '../../test/fixtures';
import { CSRF_TOKEN, renderApp } from '../../test/render-app';
import type { ZoneCreateRequest } from './types';
import { server } from '../../test/server';

const ZONES = `/delivery/zones?storeId=${baixa.id}`;

describe('delivery zones list', () => {
  it("lists the selected store's zones with their code, state and fee item", async () => {
    const state = zonesServer();

    renderApp(ZONES);

    const list = await screen.findByRole('list', { name: 'Zonas da loja' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]!).getByRole('heading', { name: `${centro.code} ${centro.name}` })).toBeInTheDocument();
    expect(within(items[0]!).getByText('Ativa')).toBeInTheDocument();
    expect(await within(items[0]!).findByText(feeNear.name!)).toBeInTheDocument();
    expect(within(items[1]!).getByRole('heading', { name: `${polana.code} ${polana.name}` })).toBeInTheDocument();
    expect(within(items[1]!).getByText('Inativa')).toBeInTheDocument();
    expect(within(items[1]!).getByText(feeFar.name!)).toBeInTheDocument();
    expect(state.lists).toEqual([`?storeId=${baixa.id}`]);
  });

  it('asks for a store before listing anything', async () => {
    const state = zonesServer();

    renderApp('/delivery/zones');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver as suas zonas de entrega.')).toBeInTheDocument();
    expect(state.lists).toEqual([]);
  });

  it('says so when the store has no zone yet, and still offers to create one', async () => {
    zonesServer({ zones: [] });

    renderApp(ZONES);

    expect(await screen.findByText('Esta loja ainda não tem zonas de entrega.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nova zona' })).toBeEnabled();
  });

  it('shows the API refusal to a user without the permission in this store', async () => {
    zonesServer();
    server.use(http.get(api('/api/v1/backoffice/delivery-zones'), () => problem(403, 'HTTP_403', 'Forbidden')));

    renderApp(ZONES);

    expect(await screen.findByText('Sem permissão para ver as zonas de entrega desta loja.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Nova zona' })).not.toBeInTheDocument();
  });

  it('shows "Sem ligação" with a retry when the zones cannot be reached', async () => {
    let calls = 0;
    zonesServer();
    server.use(
      http.get(api('/api/v1/backoffice/delivery-zones'), () => {
        calls += 1;
        return calls <= 3 ? problem(502, 'BACKEND_UNAVAILABLE') : HttpResponse.json([centro]);
      }),
    );

    const { user } = renderApp(ZONES);

    expect(await screen.findByText('Sem ligação ao serviço.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(await screen.findByRole('heading', { name: `${centro.code} ${centro.name}` })).toBeInTheDocument();
  });
});

const A_KEY = expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

type Sent = { body: unknown; idempotencyKey: string | null; csrf: string | null };

async function sent(request: Request): Promise<Sent> {
  return {
    body: await request.clone().json(),
    idempotencyKey: request.headers.get('Idempotency-Key'),
    csrf: request.headers.get('X-XSRF-TOKEN'),
  };
}

/** The create handler adds the zone to the store's list, as the server would. */
function creating(state: ReturnType<typeof zonesServer>, answer?: () => Response) {
  const posts: Sent[] = [];
  server.use(
    http.post(api('/api/v1/backoffice/delivery-zones'), async ({ request }) => {
      posts.push(await sent(request));
      const failure = answer?.();
      if (failure) return failure;
      const body = posts.at(-1)!.body as ZoneCreateRequest;
      const created = zone('20000000-0000-4000-8000-000000000009', body.code, body.name, body.feeItemId);
      state.zones.push(created);
      return HttpResponse.json(created, { status: 201 });
    }),
  );
  return posts;
}

describe('creating a zone', () => {
  it('creates a zone with a FEE item of the catalog, under one key, and shows it in the list', async () => {
    const state = zonesServer();
    const posts = creating(state);
    const { user } = renderApp(ZONES);

    await user.click(await screen.findByRole('button', { name: 'Nova zona' }));
    const dialog = screen.getByRole('dialog', { name: 'Nova zona de entrega' });
    await user.type(within(dialog).getByLabelText('Código'), 'z3');
    await user.type(within(dialog).getByLabelText('Nome'), 'Baixa');
    const fee = within(dialog).getByLabelText('Artigo de taxa');
    expect(await within(fee).findByRole('option', { name: `${feeNear.name} (${feeNear.code})` })).toBeInTheDocument();
    expect(within(fee).queryByRole('option', { name: new RegExp(dish.name!) })).not.toBeInTheDocument();
    await user.selectOptions(fee, feeNear.id!);
    await user.click(within(dialog).getByRole('button', { name: 'Criar zona' }));

    expect(await screen.findByRole('heading', { name: 'Z3 Baixa' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posts).toEqual([
      {
        body: { storeId: baixa.id, code: 'Z3', name: 'Baixa', feeItemId: feeNear.id },
        idempotencyKey: A_KEY,
        csrf: CSRF_TOKEN,
      },
    ]);
  });
});
