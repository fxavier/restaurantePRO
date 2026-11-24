import { describe, expect, it } from 'vitest';
import { availabilityLabel, destinationOf, warningsLabel } from './catalog-labels';
import { familySchema, newPriceSchema } from './schemas';

/** The first message a form would show for each field, as a user reads it under the field. */
function messages(result: { success: boolean; error?: { issues: { path: PropertyKey[]; message: string }[] } }) {
  return Object.fromEntries((result.error?.issues ?? []).map(({ path, message }) => [String(path[0]), message]));
}

describe('catalog form rules', () => {
  it('asks for the code and the name a family is created with', () => {
    expect(messages(familySchema.safeParse({ code: '  ', name: '', parentId: '' }))).toEqual({
      code: 'Indique o código.',
      name: 'Indique o nome.',
    });
  });

  it('accepts a one-letter code and name, sending the code in capitals', () => {
    expect(familySchema.parse({ code: 'c', name: 'C', parentId: '' })).toEqual({ code: 'C', name: 'C', parentId: '' });
  });

  it('asks which item a new price is for', () => {
    expect(messages(newPriceSchema.safeParse({ itemId: '', variantId: '', amount: '12,50' }))).toEqual({ itemId: 'Escolha o artigo.' });
    expect(newPriceSchema.parse({ itemId: 'x', variantId: '', amount: '12,50' }).amount).toBe('12.50');
  });
});

describe('catalog labels', () => {
  it('counts a release’s warnings in Portuguese', () => {
    expect(warningsLabel(1)).toBe('1 aviso');
    expect(warningsLabel(2)).toBe('2 avisos');
  });

  it('names an item’s availability', () => {
    expect(availabilityLabel('INACTIVE')).toBe('Inativo');
    expect(availabilityLabel('ACTIVE')).toBe('Ativo');
  });
});

describe('destinationOf', () => {
  it("names the design's destinations, and gives every code the shared route hue (COZINHA the kitchen's)", () => {
    expect(destinationOf('KITCHEN')).toEqual({ label: 'Cozinha', fill: expect.stringContaining('border-route-kitchen') });
    expect(destinationOf('COZINHA')).toEqual({ label: 'COZINHA', fill: destinationOf('KITCHEN').fill });
    expect(destinationOf('GRILL').fill).toContain('border-border bg-muted');
    expect(destinationOf(null)).toEqual({ label: 'Sem destino', fill: destinationOf('GRILL').fill });
  });
});
