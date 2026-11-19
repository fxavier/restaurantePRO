import { describe, expect, it } from 'vitest';
import { batchJson } from './expected-gross';

describe('batchJson', () => {
  it('writes expectedGross as an exact JSON number, never through a float', () => {
    const text = batchJson({ orderVersion: 4, catalogVersion: 7, lineIds: ['a'], expectedGross: '0.30' });
    expect(text).toBe('{"orderVersion":4,"catalogVersion":7,"lineIds":["a"],"expectedGross":0.30}');
    expect(JSON.parse(text)).toMatchObject({ expectedGross: 0.3 });
  });

  it('refuses what is not a plain decimal (an exponent form)', () => {
    expect(() => batchJson({ orderVersion: 4, catalogVersion: 7, lineIds: ['a'], expectedGross: String(1e21) })).toThrow(
      'Not a decimal amount',
    );
  });

  it('leaves expectedGross out when there is none', () => {
    expect(batchJson({ orderVersion: 4, catalogVersion: 7, lineIds: ['a'] })).toBe('{"orderVersion":4,"catalogVersion":7,"lineIds":["a"]}');
  });
});
