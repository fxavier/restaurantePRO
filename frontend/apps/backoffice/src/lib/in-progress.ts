import { ApiError } from '@restaurantpro/api-client';

/** FC §8: a request whose Idempotency-Key is still running waits 1 s and is resent as it was, up to three times. */
export const IN_PROGRESS_WAIT_MS = 1_000;
const IN_PROGRESS_RETRIES = 3;

/** 409 IDEMPOTENCY_IN_PROGRESS: the same request is still running on the server, its outcome is not known yet. */
export function isInProgress(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === 'IDEMPOTENCY_IN_PROGRESS';
}

/** Sends, and while the answer is "in progress" waits and sends the same request (same key) again. */
export async function sendWaitingWhileInProgress<Result>(send: () => Promise<Result>): Promise<Result> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await send();
    } catch (error) {
      if (!isInProgress(error) || attempt >= IN_PROGRESS_RETRIES) throw error;
      await new Promise((resolve) => setTimeout(resolve, IN_PROGRESS_WAIT_MS));
    }
  }
}
