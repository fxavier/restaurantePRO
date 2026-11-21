import { useState } from 'react';
import type { ZoneValues } from '../schemas';
import type { ZoneCreateRequest, ZoneView } from '../types';
import { intentFor, toCreateRequest, toUpdateRequest, type Intent } from '../zone-requests';
import { useCreateZone, useUpdateZone } from './use-zone-commands';

type Callbacks = { onSuccess: () => void; onError: (error: Error) => void };

/**
 * Saving the zone form: a new zone is created under one Idempotency-Key per intent (the same values again keep the
 * key, FC §8); an existing one is updated on the version it was read at.
 */
export function useSaveZone(storeId: string, zone: ZoneView | undefined) {
  const create = useCreateZone();
  const update = useUpdateZone();
  const [intent, setIntent] = useState<Intent<ZoneCreateRequest>>();

  const save = (values: ZoneValues, callbacks: Callbacks) => {
    if (zone) {
      update.mutate({ zoneId: zone.id!, body: toUpdateRequest(values, zone.version!) }, callbacks);
      return;
    }
    const current = intentFor(intent, toCreateRequest(storeId, values), () => crypto.randomUUID());
    setIntent(current);
    create.mutate({ body: current.body, idempotencyKey: current.key }, callbacks);
  };

  const mutation = zone ? update : create;
  return { save, error: mutation.error, isPending: mutation.isPending };
}
