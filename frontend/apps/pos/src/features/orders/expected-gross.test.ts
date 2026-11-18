import { describe, expect, it } from 'vitest';
import { batchJson, sumDecimals } from './expected-gross';

describe('sumDecimals', () => {
  it('adds exactly, where floating point would not: 0.10 + 0.20 = 0.30', () => {
    expect(sumDecimals(['0.10', '0.20'])).toBe('0.30');
    expect(sumDecimals([0.1, 0.2])).toBe('0.3');
  });

  it('adds amounts of mixed scales at the largest scale', () => {
    expect(sumDecimals(['120', '0.5', '49.99'])).toBe('170.49');
    expect(sumDecimals([120, '0.050'])).toBe('120.050');
  });

  it('adds many lines without drift', () => {
    expect(sumDecimals(Array.from({ length: 1000 }, () => '0.01'))).toBe('10.00');
    expect(sumDecimals(Array.from({ length: 200 }, () => '999999999999.99'))).toBe('199999999999998.00');
  });

  it('is 0 for nothing; keeps a sign', () => {
    expect(sumDecimals([])).toBe('0');
    expect(sumDecimals(['-1.25', '0.25'])).toBe('-1.00');
  });

  it('refuses what is not a plain decimal', () => {
    expect(() => sumDecimals([1e21])).toThrow('Not a decimal amount');
    expect(() => sumDecimals(['abc'])).toThrow('Not a decimal amount');
  });
});

describe('batchJson', () => {
  it('writes expectedGross as an exact JSON number, never through a float', () => {
    const text = batchJson({ orderVersion: 4, catalogVersion: 7, lineIds: ['a'], expectedGross: '0.30' });
    expect(text).toBe('{"orderVersion":4,"catalogVersion":7,"lineIds":["a"],"expectedGross":0.30}');
    expect(JSON.parse(text)).toMatchObject({ expectedGross: 0.3 });
  });

  it('leaves expectedGross out when there is none', () => {
    expect(batchJson({ orderVersion: 4, catalogVersion: 7, lineIds: ['a'] })).toBe('{"orderVersion":4,"catalogVersion":7,"lineIds":["a"]}');
  });
});
