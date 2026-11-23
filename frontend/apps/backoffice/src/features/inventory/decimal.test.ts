import { describe, expect, it } from 'vitest';
import { compareDecimals, isPositive, toDecimal, toInputDecimal } from './decimal';

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

describe('compareDecimals', () => {
  it.each([
    ['4.5', '5', -1],
    ['5', '5.000000', 0],
    ['5.000001', '5', 1],
    ['10', '9.999', 1],
    ['007', '7', 0],
    ['0', '0.000', 0],
    ['0.1', '0.09', 1],
  ])('compares %j with %j by its digits: %j', (a, b, expected) => {
    expect(compareDecimals(a, b)).toBe(expected);
  });
});

describe('toInputDecimal', () => {
  it.each([
    ['5.000000', '5'],
    ['2.500000', '2,5'],
    ['1234.000001', '1234,000001'],
    ['0.000000', '0'],
    ['12', '12'],
  ])('writes the decimal string %j as the pt input %j, exactly', (decimal, expected) => {
    expect(toInputDecimal(decimal)).toBe(expected);
    expect(compareDecimals(toDecimal(expected)!, decimal)).toBe(0);
  });
});
