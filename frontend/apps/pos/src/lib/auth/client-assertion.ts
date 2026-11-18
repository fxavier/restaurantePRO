/**
 * The terminal's key and its RFC 7523 client assertion (D182, FC §6). The private key is generated non-extractable,
 * so it never leaves the browser; WebCrypto's ECDSA signature is already the raw r||s form JWS ES256 requires.
 * Never log an assertion: it is a credential for its minute.
 */

const ALGORITHM = { name: 'ECDSA', namedCurve: 'P-256' } as const;
const ASSERTION_LIFETIME_SECONDS = 60;

export function generateDeviceKey(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(ALGORITHM, false, ['sign']);
}

/** The public half as the backend accepts it (`DevicePublicKey`): kty, crv, x, y and nothing else. */
export async function publicJwk(keyPair: CryptoKeyPair): Promise<{ kty: string; crv: string; x: string; y: string }> {
  const { kty, crv, x, y } = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
  if (!kty || !crv || !x || !y) throw new Error('The device key has no public point');
  return { kty, crv, x, y };
}

type AssertionInput = {
  clientId: string;
  /** The devices realm's issuer, as the bridge and the backend send it (Keycloak 26 accepts it). */
  audience: string;
  privateKey: CryptoKey;
  /** Milliseconds since the epoch. */
  now: number;
};

/** iss = sub = the client, aud = the issuer, a random jti, iat, exp = iat + 60 s; signed ES256. */
export async function signClientAssertion({ clientId, audience, privateKey, now }: AssertionInput): Promise<string> {
  const iat = Math.floor(now / 1000);
  const claims = {
    iss: clientId,
    sub: clientId,
    aud: audience,
    jti: crypto.randomUUID(),
    iat,
    exp: iat + ASSERTION_LIFETIME_SECONDS,
  };
  const signingInput = `${base64UrlJson({ alg: 'ES256', typ: 'JWT' })}.${base64UrlJson(claims)}`;
  const signature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    privateKey,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64Url(new Uint8Array(signature))}`;
}

function base64UrlJson(value: unknown): string {
  return base64Url(new TextEncoder().encode(JSON.stringify(value)));
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
