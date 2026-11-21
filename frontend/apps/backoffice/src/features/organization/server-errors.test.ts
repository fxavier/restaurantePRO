import { ApiError, type FieldError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { fieldErrors, isVersionConflict } from './server-errors';

const FIELDS = ['code', 'name'] as const;
const TAKEN = { STORE_CODE_TAKEN: { field: 'code', message: 'Código em uso.' } } as const;
const failure = (status: number, code: string, errors?: FieldError[]) => new ApiError({ status, code, title: 'x', errors });

describe('fieldErrors', () => {
  it('is about no field for a non-API error, another status, or a 400 without violations', () => {
    expect(fieldErrors(new Error('boom'), FIELDS, TAKEN)).toBeUndefined();
    expect(fieldErrors(failure(422, 'STORE_CODE_TAKEN'), FIELDS, TAKEN)).toBeUndefined();
    expect(fieldErrors(failure(400, 'VALIDATION'), FIELDS, TAKEN)).toBeUndefined();
    expect(fieldErrors(failure(400, 'VALIDATION', []), FIELDS, TAKEN)).toBeUndefined();
  });

  it('puts a 409 whose code names a field on that field, and leaves another 409 alone', () => {
    expect(fieldErrors(failure(409, 'STORE_CODE_TAKEN'), FIELDS, TAKEN)).toEqual({ fields: { code: 'Código em uso.' }, rest: [] });
    expect(fieldErrors(failure(409, 'VERSION_CONFLICT'), FIELDS, TAKEN)).toBeUndefined();
    expect(fieldErrors(failure(409, 'STORE_CODE_TAKEN'), FIELDS)).toBeUndefined();
  });

  it("puts each 400 violation on its field, the first one winning, and the rest aside", () => {
    const error = failure(400, 'VALIDATION', [
      { field: 'name', message: 'Nome obrigatório.' },
      { field: 'name', message: 'Nome longo.' },
      { field: 'timezone', message: 'Fuso inválido.' },
    ]);

    expect(fieldErrors(error, FIELDS)).toEqual({ fields: { name: 'Nome obrigatório.' }, rest: ['Nome longo.', 'Fuso inválido.'] });
  });
});

describe('isVersionConflict', () => {
  it('is only a 409 VERSION_CONFLICT from the API', () => {
    expect(isVersionConflict(failure(409, 'VERSION_CONFLICT'))).toBe(true);
    expect(isVersionConflict(failure(409, 'STORE_CODE_TAKEN'))).toBe(false);
    expect(isVersionConflict(failure(412, 'VERSION_CONFLICT'))).toBe(false);
    expect(isVersionConflict(new Error('VERSION_CONFLICT'))).toBe(false);
  });
});
