import { createHmac } from 'node:crypto';

// RFC 6238 TOTP with the dev realm's OTP policy (HmacSHA1, 6 digits, 30 s): a few lines of node:crypto instead of a
// library, for one call site.
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const PERIOD_SECONDS = 30;

/** RFC 4648 base32 (padding, spaces and case ignored). */
export function base32Decode(text: string): Buffer {
  const bytes: number[] = [];
  let value = 0;
  let bits = 0;
  for (const char of text.toUpperCase().replace(/[\s=]/g, '')) {
    const index = ALPHABET.indexOf(char);
    if (index < 0) throw new Error('not base32');
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((value >>> bits) & 0xff);
    }
  }
  return Buffer.from(bytes);
}

export function totp(secret: string, unixSeconds: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(unixSeconds / PERIOD_SECONDS)));
  const mac = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = mac[mac.length - 1]! & 0x0f;
  return ((mac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
}

export function randomBase32(length: number, random: (size: number) => Buffer): string {
  return [...random(length)].map((byte) => ALPHABET[byte % 32]).join('');
}

/**
 * A code from a period no earlier sign-in of this run used: Keycloak refuses a code twice (otpPolicyCodeReusable
 * false). The last period travels in E2E_ADMIN_TOTP_LAST_PERIOD, from the global setup to the workers.
 */
export async function freshTotp(secret: string): Promise<string> {
  const last = Number(process.env.E2E_ADMIN_TOTP_LAST_PERIOD ?? -1);
  let now = Math.floor(Date.now() / 1000);
  while (Math.floor(now / PERIOD_SECONDS) <= last) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    now = Math.floor(Date.now() / 1000);
  }
  process.env.E2E_ADMIN_TOTP_LAST_PERIOD = String(Math.floor(now / PERIOD_SECONDS));
  return totp(secret, now);
}
