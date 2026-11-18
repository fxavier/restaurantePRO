import { describe, expect, it } from 'vitest';
import { generateDeviceKey, publicJwk, signClientAssertion } from './client-assertion';

const ISSUER = 'https://keycloak.test/realms/restaurantpro-devices';
const CLIENT = 'pos-6f1c2a9e-0000-4000-8000-000000000001';

function decodePart(part: string): unknown {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')));
}

function bytesOf(part: string): Uint8Array<ArrayBuffer> {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

describe('device key', () => {
  it('is a P-256 ECDSA key pair whose private half cannot be exported', async () => {
    const keyPair = await generateDeviceKey();

    expect(keyPair.privateKey.extractable).toBe(false);
    expect(keyPair.privateKey.usages).toEqual(['sign']);
    expect(keyPair.privateKey.algorithm).toMatchObject({ name: 'ECDSA', namedCurve: 'P-256' });
    await expect(crypto.subtle.exportKey('jwk', keyPair.privateKey)).rejects.toThrow();
    await expect(crypto.subtle.exportKey('pkcs8', keyPair.privateKey)).rejects.toThrow();
  });

  it('exports only the public members the backend accepts (no private d)', async () => {
    const jwk = await publicJwk(await generateDeviceKey());

    expect(Object.keys(jwk).sort()).toEqual(['crv', 'kty', 'x', 'y']);
    expect(jwk).toMatchObject({ kty: 'EC', crv: 'P-256' });
    expect(jwk.x).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(jwk.y).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });
});

describe('client assertion (RFC 7523, ES256)', () => {
  const now = Date.UTC(2026, 10, 18, 12, 0, 0);

  it('carries iss = sub = the client, aud = the devices issuer, a random jti, iat and exp = iat + 60 s', async () => {
    const keyPair = await generateDeviceKey();

    const first = await signClientAssertion({ clientId: CLIENT, audience: ISSUER, privateKey: keyPair.privateKey, now });
    const second = await signClientAssertion({ clientId: CLIENT, audience: ISSUER, privateKey: keyPair.privateKey, now });
    const [header, payload] = first.split('.');

    expect(decodePart(header!)).toEqual({ alg: 'ES256', typ: 'JWT' });
    const claims = decodePart(payload!) as Record<string, unknown>;
    expect(claims).toMatchObject({ iss: CLIENT, sub: CLIENT, aud: ISSUER, iat: now / 1000, exp: now / 1000 + 60 });
    expect(claims.jti).toMatch(/^[0-9a-f-]{36}$/);
    expect((decodePart(second.split('.')[1]!) as Record<string, unknown>).jti).not.toBe(claims.jti);
  });

  it('is signed with a 64-byte r||s ES256 signature that verifies with the public key', async () => {
    const keyPair = await generateDeviceKey();
    const verifier = await crypto.subtle.importKey(
      'jwk',
      await publicJwk(keyPair),
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify'],
    );

    const jwt = await signClientAssertion({ clientId: CLIENT, audience: ISSUER, privateKey: keyPair.privateKey, now });
    const [header, payload, signature] = jwt.split('.');
    const signingInput = new TextEncoder().encode(`${header}.${payload}`);

    expect(bytesOf(signature!)).toHaveLength(64);
    await expect(
      crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, verifier, bytesOf(signature!), signingInput),
    ).resolves.toBe(true);
    const tampered = new TextEncoder().encode(`${header}.${payload}x`);
    await expect(
      crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, verifier, bytesOf(signature!), tampered),
    ).resolves.toBe(false);
  });

  it('uses base64url without padding in every part', async () => {
    const keyPair = await generateDeviceKey();

    const jwt = await signClientAssertion({ clientId: CLIENT, audience: ISSUER, privateKey: keyPair.privateKey, now });

    expect(jwt).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  });
});
