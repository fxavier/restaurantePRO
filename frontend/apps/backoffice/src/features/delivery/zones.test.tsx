import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { centro, dish, feeFar, feeFarNight, feeNear, polana, zone, zonesServer } from '../../test/delivery-fixtures';
import { api, baixa, problem } from '../../test/fixtures';
import { CSRF_TOKEN, renderApp } from '../../test/render-app';
import type { ZoneCreateRequest, ZoneUpdateRequest, ZoneView } from './types';
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
    expect(await within(items[0]!).findByText(feeNear.name)).toBeInTheDocument();
    expect(within(items[1]!).getByRole('heading', { name: `${polana.code} ${polana.name}` })).toBeInTheDocument();
    expect(within(items[1]!).getByText('Inativa')).toBeInTheDocument();
    expect(within(items[1]!).getByText(feeFar.name)).toBeInTheDocument();
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
function creating(state: ReturnType<typeof zonesServer>, answer?: () => Response | undefined) {
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
    expect(within(fee).queryByRole('option', { name: new RegExp(dish.name) })).not.toBeInTheDocument();
    await user.selectOptions(fee, feeNear.id);
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

  it('shows a taken code on the code field and a server violation on its field', async () => {
    const state = zonesServer();
    let calls = 0;
    creating(state, () => {
      calls += 1;
      return calls === 1
        ? problem(409, 'DELIVERY_ZONE_CODE_TAKEN', 'Conflict')
        : problem(400, 'VALIDATION', 'Bad Request', { errors: [{ field: 'name', message: 'Nome inválido.' }] });
    });
    const { user } = renderApp(ZONES);

    const dialog = await openNewZone(user);
    await fillNewZone(user, dialog);
    await user.click(within(dialog).getByRole('button', { name: 'Criar zona' }));
    expect(await within(dialog).findByText('Já existe uma zona com este código nesta loja.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Criar zona' }));
    expect(await within(dialog).findByText('Nome inválido.')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Nome')).toHaveAttribute('aria-invalid', 'true');
  });

  it('offers "Tentar novamente" without a connection, resending the same request under the same key', async () => {
    const state = zonesServer();
    let calls = 0;
    const posts = creating(state, () => {
      calls += 1;
      return calls === 1 ? problem(502, 'BACKEND_UNAVAILABLE') : undefined;
    });
    const { user } = renderApp(ZONES);

    const dialog = await openNewZone(user);
    await fillNewZone(user, dialog);
    await user.click(within(dialog).getByRole('button', { name: 'Criar zona' }));
    expect(await within(dialog).findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    expect(await screen.findByRole('heading', { name: 'Z3 Baixa' })).toBeInTheDocument();
    expect(posts).toHaveLength(2);
    expect(posts[1]).toEqual(posts[0]);
  });

  it('says the fee items cannot be listed when the catalog refuses, and creates nothing', async () => {
    const state = zonesServer();
    const posts = creating(state);
    server.use(http.get(api('/api/v1/backoffice/catalog/items'), () => problem(403, 'HTTP_403', 'Forbidden')));
    const { user } = renderApp(ZONES);

    const dialog = await openNewZone(user);
    expect(await within(dialog).findByText('Sem permissão para ver os artigos do catálogo.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Criar zona' }));
    expect(await within(dialog).findByText('Escolha o artigo de taxa.')).toBeInTheDocument();
    expect(posts).toEqual([]);
  });
});

/** The update handler changes the zone in the store's list and bumps its version, as the server would. */
function updating(state: ReturnType<typeof zonesServer>, answer?: () => Response | undefined) {
  const puts: (Sent & { id: string })[] = [];
  server.use(
    http.put(api('/api/v1/backoffice/delivery-zones/:id'), async ({ request, params }) => {
      puts.push({ ...(await sent(request)), id: String(params.id) });
      const failure = answer?.();
      if (failure) return failure;
      const body = puts.at(-1)!.body as ZoneUpdateRequest;
      const index = state.zones.findIndex((each) => each.id === params.id);
      const { version, ...change } = body;
      const changed: ZoneView = { ...state.zones[index]!, feeVariantId: undefined, ...change, version: version + 1 };
      state.zones[index] = changed;
      return HttpResponse.json(changed);
    }),
  );
  return puts;
}

describe('editing a zone', () => {
  it("changes a zone's name, fee variant and state, guarded by its version", async () => {
    const state = zonesServer();
    const puts = updating(state);
    const { user } = renderApp(ZONES);

    await user.click(await screen.findByRole('button', { name: `Editar ${polana.name}` }));
    const dialog = screen.getByRole('dialog', { name: `Editar zona ${polana.code}` });
    expect(within(dialog).queryByLabelText('Código')).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Artigo de taxa')).toHaveValue(feeFar.id);
    const variant = await within(dialog).findByLabelText('Variante');
    expect(variant).toHaveValue(feeFarNight.id);
    await user.clear(within(dialog).getByLabelText('Nome'));
    await user.type(within(dialog).getByLabelText('Nome'), 'Polana Cimento');
    await user.selectOptions(variant, '');
    await user.click(within(dialog).getByLabelText('Zona ativa'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByRole('heading', { name: 'Z2 Polana Cimento' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(puts).toEqual([
      {
        id: polana.id,
        body: { name: 'Polana Cimento', feeItemId: feeFar.id, active: true, version: 0 },
        idempotencyKey: null,
        csrf: CSRF_TOKEN,
      },
    ]);
  });

  it('offers the variants of a newly picked fee item, and sends the one chosen', async () => {
    const state = zonesServer();
    const puts = updating(state);
    const { user } = renderApp(ZONES);

    await user.click(await screen.findByRole('button', { name: `Editar ${centro.name}` }));
    const dialog = screen.getByRole('dialog', { name: `Editar zona ${centro.code}` });
    expect(within(dialog).queryByLabelText('Variante')).not.toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Artigo de taxa'), feeFar.id);
    await user.selectOptions(await within(dialog).findByLabelText('Variante'), feeFarNight.id);
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(puts.map((put) => put.body)).toEqual([
      { name: centro.name, feeItemId: feeFar.id, feeVariantId: feeFarNight.id, active: true, version: 0 },
    ]);
  });

  it('shows the zone as it now is when someone changed it meanwhile, and saves on the new version', async () => {
    const state = zonesServer();
    let calls = 0;
    const puts = updating(state, () => {
      calls += 1;
      if (calls > 1) return undefined;
      state.zones[0] = { ...centro, name: 'Centro Histórico', version: 1 };
      return problem(409, 'VERSION_CONFLICT', 'Conflict');
    });
    const { user } = renderApp(ZONES);

    await user.click(await screen.findByRole('button', { name: `Editar ${centro.name}` }));
    let dialog = screen.getByRole('dialog', { name: `Editar zona ${centro.code}` });
    await user.click(within(dialog).getByLabelText('Zona ativa'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    dialog = await screen.findByRole('dialog', { name: `Editar zona ${centro.code}` });
    expect(await within(dialog).findByText('A zona foi alterada por outra pessoa. Reveja os dados atuais e guarde de novo.')).toBeInTheDocument();
    await waitFor(() => expect(within(dialog).getByLabelText('Nome')).toHaveValue('Centro Histórico'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(puts.map((put) => put.body)).toEqual([
      { name: centro.name, feeItemId: centro.feeItemId, active: false, version: 0 },
      { name: 'Centro Histórico', feeItemId: centro.feeItemId, active: true, version: 1 },
    ]);
  });
});

type User = ReturnType<typeof renderApp>['user'];

async function openNewZone(user: User) {
  await user.click(await screen.findByRole('button', { name: 'Nova zona' }));
  return screen.getByRole('dialog', { name: 'Nova zona de entrega' });
}

async function fillNewZone(user: User, dialog: HTMLElement) {
  await user.type(within(dialog).getByLabelText('Código'), 'z3');
  await user.type(within(dialog).getByLabelText('Nome'), 'Baixa');
  await within(dialog).findByRole('option', { name: `${feeNear.name} (${feeNear.code})` });
  await user.selectOptions(within(dialog).getByLabelText('Artigo de taxa'), feeNear.id);
}
