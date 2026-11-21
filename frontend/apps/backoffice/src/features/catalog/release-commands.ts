import type { ApiClient } from '@restaurantpro/api-client';
import { z } from 'zod';
import { sendWaitingWhileInProgress } from '../../lib/in-progress';
import type { PendingCommand } from '../../lib/step-up';
import type { ReleaseSummary } from './types';

const BASE = '/api/v1/backoffice/catalog/releases';
const PUBLISH_PATH = /^\/api\/v1\/backoffice\/catalog\/releases\/([0-9a-fA-F-]{36})\/publish$/;

/**
 * One publication intent (spec §8.3, FC §8): the release, the optional note and the Idempotency-Key made when the
 * user confirmed. Every resend, and the resume after a step-up, carries the same key, so the server publishes once.
 */
export type Publication = { releaseId: string; storeId: string; note: string; idempotencyKey: string };

/** A 409 IDEMPOTENCY_IN_PROGRESS waits and is resent with the same key. */
export function publishRelease(api: ApiClient, { releaseId, note, idempotencyKey }: Publication) {
  return sendWaitingWhileInProgress(() =>
    api.POST(`${BASE}/{id}/publish`, {
      params: { path: { id: releaseId }, header: { 'Idempotency-Key': idempotencyKey } },
      body: note ? { note } : {},
    }),
  ).then(({ data }) => data as ReleaseSummary);
}

/** The catalog screen's address for a store's releases: where a step-up comes back to. */
export function releasesPath(storeId: string): string {
  return `/catalog?${new URLSearchParams({ storeId, tab: 'versoes' })}`;
}

/** What a step-up keeps of a publication (FC §6): the request and its key, no secret. */
export function toPendingPublication(publication: Publication, tenantId: string): Omit<PendingCommand, 'savedAt'> {
  return {
    method: 'POST',
    path: `${BASE}/${publication.releaseId}/publish`,
    body: { note: publication.note },
    idempotencyKey: publication.idempotencyKey,
    returnTo: releasesPath(publication.storeId),
    label: 'Publicar versão do catálogo',
    tenantId,
  };
}

const pendingBody = z.object({ note: z.string().max(500) });

/** The publication a stored command is, for this store; undefined for any other command, which is left alone. */
export function fromPendingPublication(pending: PendingCommand, storeId: string): Publication | undefined {
  const match = PUBLISH_PATH.exec(pending.path);
  const body = pendingBody.safeParse(pending.body);
  if (pending.method !== 'POST' || !match || !body.success || !pending.idempotencyKey) return undefined;
  if (pending.returnTo !== releasesPath(storeId)) return undefined;
  return { releaseId: match[1]!, storeId, note: body.data.note, idempotencyKey: pending.idempotencyKey };
}

/**
 * Publishing needs CATALOG_PUBLISH in the store (ReleaseService), company-wide or for this store; the button is hidden
 * without it, as a convenience: the server decides.
 */
export function canPublish(permissions: readonly string[], storeId: string): boolean {
  return permissions.some((permission) => permission === 'PERM_CATALOG_PUBLISH' || permission === `PERM_CATALOG_PUBLISH@${storeId}`);
}
