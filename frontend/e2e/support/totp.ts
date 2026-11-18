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
 * The first period whose code no earlier sign-in of this run can have used: Keycloak refuses a code twice
 * (otpPolicyCodeReusable false). The global setup's last period travels in E2E_ADMIN_TOTP_LAST_PERIOD; a retried test
 * runs in a new worker that cannot know what the failed attempt used, so on a retry the current period is spent too.
 */
export function firstUsablePeriod(unixSeconds: number, lastUsedPeriod: number, retry: number): number {
  const current = Math.floor(unixSeconds / PERIOD_SECONDS);
  return Math.max(current, lastUsedPeriod + 1, retry > 0 ? current + 1 : current);
}

/** A TOTP code from the first usable period, waiting for it if needed. `retry` is the test's `testInfo.retry`. */
export async function freshTotp(secret: string, retry = 0): Promise<string> {
  const last = Number(process.env.E2E_ADMIN_TOTP_LAST_PERIOD ?? -1);
  const period = firstUsablePeriod(Date.now() / 1000, last, retry);
  while (Math.floor(Date.now() / 1000 / PERIOD_SECONDS) < period) {
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  process.env.E2E_ADMIN_TOTP_LAST_PERIOD = String(period);
  return totp(secret, period * PERIOD_SECONDS);
}
