import { describe, expect, it } from 'vitest';
import { isPin, normaliseCode, normalisePin } from './pin';

describe('PIN and employee code', () => {
  it('keeps only the first 8 digits of a PIN', () => {
    expect(normalisePin('12a3 4-5678 9')).toBe('12345678');
  });

  it('accepts 4 to 8 digits', () => {
    expect(isPin('123')).toBe(false);
    expect(isPin('1234')).toBe(true);
    expect(isPin('12345678')).toBe(true);
  });

  it('drops spaces from a scanned code and keeps 20 characters', () => {
    expect(normaliseCode(' EMP 0042\n')).toBe('EMP0042');
    expect(normaliseCode('A'.repeat(25))).toHaveLength(20);
  });
});
