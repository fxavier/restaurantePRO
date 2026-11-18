import { expect, test } from '@playwright/test';
import { totp } from './totp';

// RFC 6238 appendix B, SHA-1: the secret is the ASCII "12345678901234567890"; the 6-digit code is the last six digits
// of the published 8-digit value.
const RFC_SECRET = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';

test('the TOTP helper gives the RFC 6238 SHA-1 test codes', () => {
  expect(totp(RFC_SECRET, 59)).toBe('287082');
  expect(totp(RFC_SECRET, 1111111109)).toBe('081804');
  expect(totp(RFC_SECRET, 1234567890)).toBe('005924');
  expect(totp(RFC_SECRET, 20000000000)).toBe('353130');
});
