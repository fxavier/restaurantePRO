import { describe, expect, it } from 'vitest';
import { isPositive, toDecimal } from './decimal';

describe('toDecimal', () => {
  it.each([
    ['0,5', '0.5'],
    ['12.75', '12.75'],
    [' 3 ', '3'],
    ['-2', '-2'],
    ['+4,10', '4.10'],
    ['0', '0'],
  ])('rewrites %j as the decimal string %j', (input, expected) => {
    expect(toDecimal(input)).toBe(expected);
  });

  it.each(['', 'muito', '1.000,5', '1,', ',5', '--1', '1e3', '2 kg'])('refuses %j', (input) => {
    expect(toDecimal(input)).toBeUndefined();
  });
});

describe('isPositive', () => {
  it.each([
    ['0.5', true],
    ['3', true],
    ['0', false],
    ['0.000', false],
    ['-2', false],
    ['-0.1', false],
  ])('%j is above zero: %j', (value, expected) => {
    expect(isPositive(value)).toBe(expected);
  });
});
