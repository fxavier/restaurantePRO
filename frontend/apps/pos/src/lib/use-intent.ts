import { ApiError } from '@restaurantpro/api-client';
import { useRef, useState } from 'react';
import { isOffline, isUncodedConflict } from './error-messages';

/** FC §8: a 409 "in progress" (the same key still running) waits 1 s and resends the same key, up to three times. */
export const IN_PROGRESS_WAIT_MS = 1_000;
const IN_PROGRESS_RETRIES = 3;

/** The same key is still running on the server (FE-F25): its outcome is not known yet. */
export function isInProgress(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === 'IDEMPOTENCY_IN_PROGRESS';
}

/** An outcome the client cannot know (no answer, or still running): the same key is kept for "Tentar novamente". */
export function isUnknownOutcome(error: unknown): boolean {
  return error instanceof ApiError && (isOffline(error) || isInProgress(error));
}

export type Intent<Request, Meta> = { key: string; request: Request; meta: Meta };

type Options<Request, Result, Meta> = {
  onSuccess?: (result: Result, intent: Intent<Request, Meta>) => void;
  onError?: (error: Error, intent: Intent<Request, Meta>) => void;
  /** A failed intent given up: it may still have happened, so what it changes is read again. */
  onDiscard?: (intent: Intent<Request, Meta>) => void;
};

/**
 * One user intent at a time for a command whose contract requires an `Idempotency-Key` (FC §8): `start` makes a new
 * key (`crypto.randomUUID()`); while it is in flight a second `start` is ignored, so a double tap sends one request.
 * An unknown outcome keeps the intent, and `retry` resends it with the same key; a definitive answer drops it. Never
 * retries on its own, except a 409 "in progress", which waits and resends the same key. An older server's uncoded 409
 * may be either "in progress" or a stale version: it is resent once, the same way, and refused again it is a conflict.
 *
 * @param send the request, with the key as its `Idempotency-Key` header
 * @typeParam Meta what the screen needs to describe the intent (an item's name), never sent
 */
export function useIntent<Request, Result, Meta = undefined>(
  send: (request: Request, key: string) => Promise<Result>,
  { onSuccess, onError, onDiscard }: Options<Request, Result, Meta> = {},
) {
  const [intent, setIntent] = useState<Intent<Request, Meta>>();
  const [last, setLast] = useState<Intent<Request, Meta>>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error>();
  // A ref, not the state: two taps in one tick both see the state from before the first.
  const inFlight = useRef(false);

  async function run(next: Intent<Request, Meta>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setPending(true);
    setError(undefined);
    setIntent(next);
    setLast(next);
    try {
      const result = await sendWaitingWhileInProgress(() => send(next.request, next.key));
      setIntent(undefined);
      onSuccess?.(result, next);
    } catch (thrown) {
      const failure = thrown instanceof Error ? thrown : new Error('Unexpected failure');
      setError(failure);
      if (!isUnknownOutcome(failure)) setIntent(undefined);
      onError?.(failure, next);
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }

  return {
    /** A new intent, with a new key; ignored while another is in flight or waits for its retry. */
    start(request: Request, meta: Meta) {
      if (intent) return;
      void run({ key: crypto.randomUUID(), request, meta });
    },
    /** The same intent again, with the same key. */
    retry() {
      if (intent) void run(intent);
    },
    /** Gives the failed intent up; the next `start` is a new intent. */
    discard() {
      if (inFlight.current) return;
      if (intent) onDiscard?.(intent);
      setIntent(undefined);
      setError(undefined);
    },
    /** The intent in flight, or the one waiting for "Tentar novamente". */
    intent,
    /** The latest intent, whatever its outcome: what a refusal was about. */
    last,
    pending,
    error,
  };
}

async function sendWaitingWhileInProgress<Result>(send: () => Promise<Result>): Promise<Result> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await send();
    } catch (error) {
      const again = isInProgress(error) ? attempt < IN_PROGRESS_RETRIES : isUncodedConflict(error) && attempt === 0;
      if (!again) throw error;
      await new Promise((resolve) => setTimeout(resolve, IN_PROGRESS_WAIT_MS));
    }
  }
}
