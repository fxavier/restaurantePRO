export type Intent<Body> = { body: Body; key: string };

/**
 * One Idempotency-Key per user intent (FC §8): the same request sent again (a retry, a second press after "Sem
 * ligação") keeps the key of the previous one; a changed request is a new intent with a new key.
 */
export function nextIntent<Body>(previous: Intent<Body> | undefined, body: Body, newKey: () => string): Intent<Body> {
  return previous && JSON.stringify(previous.body) === JSON.stringify(body) ? previous : { body, key: newKey() };
}
