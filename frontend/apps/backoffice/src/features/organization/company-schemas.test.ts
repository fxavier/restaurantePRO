import { describe, expect, it } from 'vitest';
import { companyProfileFormValues } from './company-schemas';

describe('companyProfileFormValues', () => {
  it("is the profile's name, tax number, country and currency", () => {
    expect(companyProfileFormValues({ name: 'Restaurante A, Lda', taxId: '400123456', country: 'MZ', currency: 'MZN' })).toEqual({
      name: 'Restaurante A, Lda',
      taxId: '400123456',
      country: 'MZ',
      currency: 'MZN',
    });
  });

  it('leaves blank what the server did not send', () => {
    expect(companyProfileFormValues({})).toEqual({ name: '', taxId: '', country: '', currency: '' });
  });
});
