import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { onFields } from './zone-errors';

const error = (status: number, code: string, errors?: { field: string; message: string }[]) =>
  new ApiError({ status, code, title: code, ...(errors ? { errors } : {}) });

describe('onFields', () => {
  it('puts a taken code on the code field', () => {
    expect(onFields(error(409, 'DELIVERY_ZONE_CODE_TAKEN'))).toEqual([
      { field: 'code', message: 'Já existe uma zona com este código nesta loja.' },
    ]);
  });

  it("puts a 400's violations on their fields and the rest together on the form", () => {
    expect(
      onFields(
        error(400, 'VALIDATION', [
          { field: 'name', message: 'Nome inválido.' },
          { field: 'storeId', message: 'Loja inválida.' },
          { field: 'feeItemId', message: 'Taxa inválida.' },
          { field: 'other', message: 'Outro.' },
        ]),
      ),
    ).toEqual([
      { field: 'name', message: 'Nome inválido.' },
      { field: 'feeItemId', message: 'Taxa inválida.' },
      { field: 'root', message: 'Loja inválida. Outro.' },
    ]);
    expect(onFields(error(400, 'VALIDATION', [{ field: 'code', message: 'C.' }]))).toEqual([{ field: 'code', message: 'C.' }]);
  });

  it('leaves every other answer to the general error', () => {
    expect(onFields(error(409, 'VERSION_CONFLICT'))).toBeUndefined();
    expect(onFields(error(400, 'VALIDATION'))).toBeUndefined();
    expect(onFields(error(400, 'VALIDATION', []))).toBeUndefined();
    expect(onFields(error(422, 'DELIVERY_ZONE_CODE_TAKEN'))).toBeUndefined();
    expect(onFields(new Error('boom'))).toBeUndefined();
  });
});
