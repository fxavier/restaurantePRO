import { cleanup, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { resetLoginGuard } from '../../lib/browser';
import { catalogReference, page, release, type ReleaseSummary } from './test-fixtures';
import { admin, api, baixa, problem, signedIn, stepUpRequired } from '../../test/fixtures';
import { renderApp } from '../../test/render-app';
import { server } from '../../test/server';

const VERSIONS = `/catalog?storeId=${baixa.id}&tab=versoes`;
const publisher = { ...admin, permissions: [...admin.permissions, `PERM_CATALOG_PUBLISH@${baixa.id}`] };
const A_KEY = expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

const draft = release('aa000000-0000-4000-8000-000000000003', 'DRAFT', { createdAt: '2026-11-20T09:00:00Z' });
const validated = release('aa000000-0000-4000-8000-000000000002', 'VALIDATED', {
  createdAt: '2026-11-19T10:30:00Z',
  validatedAt: '2026-11-19T11:00:00Z',
  validationWarnings: [{ code: 'ITEM_WITHOUT_BARCODE', itemCode: 'C09', message: 'Bife da Vazia não tem código de barras' }],
});
const published = release('aa000000-0000-4000-8000-000000000001', 'PUBLISHED', {
  createdAt: '2026-11-01T08:00:00Z',
  effectiveAt: '2026-11-01T09:15:00Z',
});

type Sent = { path: string; body: unknown; idempotencyKey: string | null };

/** A store's releases, as the server keeps them; every command is recorded with its Idempotency-Key. */
function releasesServer(initial: ReleaseSummary[] = [validated, published], session = publisher) {
  const state = { releases: [...initial], reads: [] as string[], sent: [] as Sent[] };
  server.use(...signedIn({ session }), ...catalogReference());
  server.use(
    http.get(api('/api/v1/backoffice/stores/:id'), () => HttpResponse.json(baixa)),
    http.get(api('/api/v1/backoffice/catalog/releases'), ({ request }) => {
      state.reads.push(new URL(request.url).search);
      return HttpResponse.json(page(state.releases, 20));
    }),
  );
  return state;
}

async function record(state: { sent: Sent[] }, request: Request, path: string) {
  const text = await request.clone().text();
  state.sent.push({ path, body: text ? JSON.parse(text) : undefined, idempotencyKey: request.headers.get('Idempotency-Key') });
}

function rows() {
  const table = screen.getByRole('table', { name: 'Versões do catálogo da loja' });
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').slice(0, 4).map((cell) => cell.textContent));
}

describe('Versões', () => {
  it('asks for a store first: a release belongs to one', async () => {
    const state = releasesServer();

    renderApp('/catalog?tab=versoes');

    expect(await screen.findByText('Escolha uma loja na barra superior para ver as versões do catálogo.')).toBeInTheDocument();
    expect(state.reads).toEqual([]);
  });

  it("lists the store's releases, newest first, with their state and times in the store's timezone", async () => {
    const state = releasesServer();

    renderApp(VERSIONS);

    await screen.findByRole('table', { name: 'Versões do catálogo da loja' });
    expect(rows()).toEqual([
      ['Validada', '19/11/26, 12:30', '19/11/26, 13:00', '—'],
      ['Publicada', '01/11/26, 10:00', '—', '01/11/26, 11:15'],
    ]);
    expect(state.reads).toEqual([`?storeId=${baixa.id}&page=0&size=20`]);
  });

  it('shows the warnings a validation left on a release', async () => {
    releasesServer();

    const { user } = renderApp(VERSIONS);

    await user.click(await screen.findByText('1 aviso'));
    expect(screen.getByText('C09 · Bife da Vazia não tem código de barras')).toBeVisible();
  });

  it('starts a new release for the store', async () => {
    const state = releasesServer([published]);
    server.use(
      http.post(api('/api/v1/backoffice/catalog/releases'), async ({ request }) => {
        await record(state, request, '/releases');
        state.releases.unshift(draft);
        return HttpResponse.json(draft);
      }),
    );

    const { user } = renderApp(VERSIONS);

    await user.click(await screen.findByRole('button', { name: 'Nova versão' }));

    await waitFor(() => expect(rows()[0]?.[0]).toBe('Rascunho'));
    expect(state.sent).toEqual([{ path: '/releases', body: { storeId: baixa.id }, idempotencyKey: null }]);
  });

  it('validates a draft, and explains a validation that found problems', async () => {
    const state = releasesServer([draft, published]);
    let attempts = 0;
    server.use(
      http.post(api('/api/v1/backoffice/catalog/releases/:id/validate'), async ({ request, params }) => {
        await record(state, request, `/releases/${String(params.id)}/validate`);
        attempts += 1;
        if (attempts === 1) {
          return problem(422, undefined, 'Unprocessable Content', {
            detail: 'The catalog cannot be published: 1 problem(s).',
            errors: [{ code: 'ITEM_WITHOUT_PRICE', itemId: 'x', itemCode: 'C09', message: 'no price' }],
          });
        }
        state.releases = state.releases.map((one) => (one.id === draft.id ? { ...one, status: 'VALIDATED', validatedAt: '2026-11-20T09:05:00Z' } : one));
        return HttpResponse.json(state.releases[0]);
      }),
    );

    const { user } = renderApp(VERSIONS);

    await user.click(await screen.findByRole('button', { name: 'Validar' }));
    expect(
      await screen.findByText(
        'O catálogo tem problemas que impedem a validação (por exemplo, artigos sem preço ou sem taxa de IVA). Corrija-os e tente de novo.',
      ),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Validar' }));
    await waitFor(() => expect(rows()[0]?.[0]).toBe('Validada'));
    expect(state.sent.map(({ path }) => path)).toEqual([`/releases/${draft.id}/validate`, `/releases/${draft.id}/validate`]);
  });
});

describe('publishing a release', () => {
  /** The publish endpoint answers in turn with `answers`, then publishes. */
  function publishing(state: ReturnType<typeof releasesServer>, answers: (() => Response)[] = []) {
    server.use(
      http.post(api('/api/v1/backoffice/catalog/releases/:id/publish'), async ({ request, params }) => {
        await record(state, request, `/releases/${String(params.id)}/publish`);
        const answer = answers.shift();
        if (answer) return answer();
        state.releases = state.releases.map((one) =>
          one.id === params.id ? { ...one, status: 'PUBLISHED', effectiveAt: '2026-11-20T10:00:00Z' } : one.status === 'PUBLISHED' ? { ...one, status: 'RETIRED' } : one,
        );
        return HttpResponse.json(state.releases.find((one) => one.id === params.id));
      }),
    );
  }

  it('is not offered to a user without CATALOG_PUBLISH in the store', async () => {
    releasesServer([validated], admin);

    renderApp(VERSIONS);

    expect(await screen.findByRole('button', { name: 'Validar' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Publicar' })).not.toBeInTheDocument();
  });

  it('publishes a validated release with a note, resending the same intent with the same key when no answer came', async () => {
    const state = releasesServer();
    publishing(state, [() => problem(503, 'BACKEND_UNAVAILABLE')]);

    const { user } = renderApp(VERSIONS);

    await user.click(await screen.findByRole('button', { name: 'Publicar' }));
    const dialog = screen.getByRole('dialog', { name: 'Publicar versão' });
    await user.type(within(dialog).getByLabelText('Nota (opcional)'), 'Carta de verão');
    await user.click(within(dialog).getByRole('button', { name: 'Publicar' }));
    expect(await within(dialog).findByText('Sem ligação — a operação não foi confirmada.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(rows().map(([status]) => status)).toEqual(['Publicada', 'Retirada']));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(state.sent).toEqual([
      { path: `/releases/${validated.id}/publish`, body: { note: 'Carta de verão' }, idempotencyKey: A_KEY },
      { path: `/releases/${validated.id}/publish`, body: { note: 'Carta de verão' }, idempotencyKey: state.sent[0]!.idempotencyKey },
    ]);
  });

  it('asks for a fresh second factor, and on return continues the same publication under the same key', async () => {
    const state = releasesServer();
    publishing(state, [() => stepUpRequired(300)]);

    const first = renderApp(VERSIONS);

    await first.user.click(await screen.findByRole('button', { name: 'Publicar' }));
    const dialog = screen.getByRole('dialog', { name: 'Publicar versão' });
    await first.user.click(within(dialog).getByRole('button', { name: 'Publicar' }));
    expect(await within(dialog).findByText('Para publicar, confirme a sua identidade.')).toBeInTheDocument();
    await first.user.click(within(dialog).getByRole('button', { name: 'Confirmar identidade' }));

    const returnTo = `/catalog?storeId=${baixa.id}&tab=versoes`;
    expect(first.leave).toHaveBeenCalledWith(`/bff/step-up?acr_values=2&max_age=300&return=${encodeURIComponent(returnTo)}`);

    // The BFF brings the browser back: a new page load on the same screen.
    cleanup();
    resetLoginGuard();
    const { user } = renderApp(returnTo);

    const resume = await screen.findByRole('region', { name: 'Publicação por continuar' });
    await user.click(within(resume).getByRole('button', { name: 'Continuar' }));

    await waitFor(() => expect(rows().map(([status]) => status)).toEqual(['Publicada', 'Retirada']));
    expect(screen.queryByRole('region', { name: 'Publicação por continuar' })).not.toBeInTheDocument();
    expect(state.sent).toEqual([
      { path: `/releases/${validated.id}/publish`, body: {}, idempotencyKey: A_KEY },
      { path: `/releases/${validated.id}/publish`, body: {}, idempotencyKey: state.sent[0]!.idempotencyKey },
    ]);
    expect(sessionStorage.length).toBe(0);
  });

  it('explains a release that changed state meanwhile, and shows the list as it is now', async () => {
    const state = releasesServer();
    publishing(state, [
      () => {
        state.releases = state.releases.map((one) => (one.id === validated.id ? { ...one, status: 'RETIRED' } : one));
        return problem(409, 'ILLEGAL_RELEASE_STATE', 'Conflict');
      },
    ]);

    const { user } = renderApp(VERSIONS);

    await user.click(await screen.findByRole('button', { name: 'Publicar' }));
    const dialog = screen.getByRole('dialog', { name: 'Publicar versão' });
    await user.click(within(dialog).getByRole('button', { name: 'Publicar' }));

    expect(await within(dialog).findByText('A versão já mudou de estado. A lista foi atualizada; reveja-a e tente de novo.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    await waitFor(() => expect(rows().map(([status]) => status)).toEqual(['Retirada', 'Publicada']));
  });
});
