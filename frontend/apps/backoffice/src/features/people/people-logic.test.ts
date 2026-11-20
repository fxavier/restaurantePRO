import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { toCreateRequest, toUpdateRequest, type AccountValues } from './account-requests';
import { fieldErrors } from './field-errors';
import { nextIntent } from './intent';
import { permissionName } from './schemas';
import { selectedTab, visibleTabs } from './tabs';

describe('visibleTabs', () => {
  it('shows a tab for its permission held company-wide or in one store, in Stitch order', () => {
    expect(visibleTabs(['PERM_SUPPORT_ACCESS_MANAGE', 'PERM_EMPLOYEE_MANAGE@store-1']).map((tab) => tab.id)).toEqual([
      'empregados',
      'suporte',
    ]);
  });

  it('does not take a longer permission name for the tab one', () => {
    expect(visibleTabs(['PERM_ROLE_MANAGER', 'PERM_USER_MANAGE_X', 'ROLE_MANAGE'])).toEqual([]);
  });
});

describe('selectedTab', () => {
  const tabs = visibleTabs(['PERM_EMPLOYEE_MANAGE', 'PERM_ROLE_MANAGE']);

  it('is the tab ?tab= names when it is visible', () => {
    expect(selectedTab('funcoes', tabs)).toBe('funcoes');
  });

  it('is the first visible tab otherwise, and none without any', () => {
    expect(selectedTab('suporte', tabs)).toBe('empregados');
    expect(selectedTab(null, tabs)).toBe('empregados');
    expect(selectedTab('empregados', [])).toBeUndefined();
  });
});

describe('fieldErrors', () => {
  const fields = ['code', 'name'];

  it('puts a conflict whose code names a field on that field, in Portuguese', () => {
    const taken = new ApiError({ status: 409, code: 'EMPLOYEE_CODE_TAKEN', title: 'taken' });
    expect(fieldErrors(taken, fields, { EMPLOYEE_CODE_TAKEN: 'code' })).toEqual([
      { field: 'code', message: 'Já existe um empregado com este código.' },
    ]);
  });

  it("puts a 400's violations on their fields and the rest together on the form", () => {
    const invalid = new ApiError({
      status: 400,
      code: 'VALIDATION',
      title: 'Bad',
      errors: [
        { field: 'name', message: 'demasiado longo' },
        { field: 'pin', message: 'inválido' },
        { field: 'other', message: 'também' },
      ],
    });
    expect(fieldErrors(invalid, fields)).toEqual([
      { field: 'name', message: 'demasiado longo' },
      { field: 'root', message: 'inválido também' },
    ]);
  });

  it('leaves the rest to the caller: other answers, a 400 without violations, a conflict naming no field', () => {
    expect(fieldErrors(new Error('x'), fields)).toBeUndefined();
    expect(fieldErrors(new ApiError({ status: 400, code: 'BAD', title: 'Bad' }), fields)).toBeUndefined();
    expect(fieldErrors(new ApiError({ status: 400, code: 'BAD', title: 'Bad', errors: [] }), fields)).toBeUndefined();
    expect(fieldErrors(new ApiError({ status: 422, code: 'X', title: 'x', errors: [{ field: 'name', message: 'm' }] }), fields)).toBeUndefined();
    expect(fieldErrors(new ApiError({ status: 409, code: 'VERSION_CONFLICT', title: 'c' }), fields, { OTHER: 'code' })).toBeUndefined();
  });

  it('adds no form-level entry when every violation names a field', () => {
    const invalid = new ApiError({ status: 400, code: 'V', title: 'Bad', errors: [{ field: 'code', message: 'm' }] });
    expect(fieldErrors(invalid, fields)).toEqual([{ field: 'code', message: 'm' }]);
  });
});

describe('nextIntent', () => {
  let n = 0;
  const newKey = () => `key-${++n}`;

  it('keeps the key for the same request and makes a new one for a changed request', () => {
    const first = nextIntent(undefined, { a: 1 }, newKey);
    expect(nextIntent(first, { a: 1 }, newKey)).toBe(first);
    const changed = nextIntent(first, { a: 2 }, newKey);
    expect(changed).toEqual({ body: { a: 2 }, key: expect.not.stringMatching(first.key) });
  });
});

describe('account requests', () => {
  const values: AccountValues = { username: 'carla', active: false, support: false, roleIds: ['r1'], employeeId: 'e1' };

  it('sends the employee link only for an account that is not a support one, and only when chosen', () => {
    expect(toCreateRequest(values)).toEqual({ username: 'carla', roleIds: ['r1'], support: false, employeeId: 'e1' });
    expect(toCreateRequest({ ...values, support: true })).toEqual({ username: 'carla', roleIds: ['r1'], support: true });
    expect(toCreateRequest({ ...values, employeeId: '' })).toEqual({ username: 'carla', roleIds: ['r1'], support: false });
  });

  it('replaces state, roles and link with the loaded version', () => {
    expect(toUpdateRequest(values, 7)).toEqual({ active: false, roleIds: ['r1'], employeeId: 'e1', version: 7 });
    expect(toUpdateRequest({ ...values, employeeId: '' }, 0)).toEqual({ active: false, roleIds: ['r1'], version: 0 });
  });
});

describe('permissionName', () => {
  it('is the typed name in capitals without surrounding spaces', () => {
    expect(permissionName('  order_view ')).toBe('ORDER_VIEW');
  });

  it('is undefined when the text cannot be a permission name', () => {
    expect(permissionName('')).toBeUndefined();
    expect(permissionName('_ORDER')).toBeUndefined();
    expect(permissionName('ORDER VIEW')).toBeUndefined();
    expect(permissionName('ORDER-VIEW')).toBeUndefined();
    expect(permissionName('X1')).toBeUndefined();
  });
});
