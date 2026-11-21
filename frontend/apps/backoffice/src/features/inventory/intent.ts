/** A user's intent: the request it sends and the Idempotency-Key every resend of it carries (FC §8). */
export type Intent<Body> = { body: Body; idempotencyKey: string };

/**
 * The intent a submit belongs to: the same body as the last one is the same intent (a retry, same key); a corrected
 * body is a new one (new key), since the server refuses one key for two different requests.
 */
export function nextIntent<Body>(previous: Intent<Body> | undefined, body: Body, newKey: () => string): Intent<Body> {
  if (previous && JSON.stringify(previous.body) === JSON.stringify(body)) return previous;
  return { body, idempotencyKey: newKey() };
}

