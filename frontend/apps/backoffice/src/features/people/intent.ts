/** One user intent of a command whose contract requires an Idempotency-Key (FC §8): the request and its key. */
export type Intent<Body> = { body: Body; key: string };

/**
 * The intent a submit belongs to: the same request again is the same intent and keeps its key (a retry the user made
 * after an unknown outcome); a changed request is a new intent with a new key.
 */
export function nextIntent<Body>(previous: Intent<Body> | undefined, body: Body, newKey: () => string): Intent<Body> {
  return previous && JSON.stringify(previous.body) === JSON.stringify(body) ? previous : { body, key: newKey() };
}
