import { describe, expect, it } from 'vitest';
import { CODE_CHARACTERS, CODE_LENGTH, formatCode, normaliseCode } from './enrolment-code';

describe('enrolment code', () => {
  it('is 24 characters of Crockford base32, as the backend issues it', () => {
    expect(CODE_LENGTH).toBe(24);
    expect(CODE_CHARACTERS).toBe('0123456789ABCDEFGHJKMNPQRSTVWXYZ');
  });

  it('ignores case, spaces and dashes, as the backend does', () => {
    expect(normaliseCode('abcd-efgh 2345')).toBe('ABCDEFGH2345');
  });

  it('reads the letters Crockford excludes as the digits they look like (O as 0, I and L as 1)', () => {
    expect(normaliseCode('OoIiLl')).toBe('001111');
  });

  it('drops anything else, such as U or punctuation', () => {
    expect(normaliseCode('A.U!B')).toBe('AB');
  });

  it('stops at 24 characters (a scanner may send a newline or more)', () => {
    expect(normaliseCode('1'.repeat(30))).toHaveLength(24);
  });

  it('shows the code in groups of four, so it is easy to compare with the paper', () => {
    expect(formatCode('ABCDEFGH23')).toBe('ABCD EFGH 23');
    expect(formatCode('')).toBe('');
  });
});
