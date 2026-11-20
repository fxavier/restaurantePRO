import type { UserCreateRequest, UserUpdateRequest } from './types';

/** The account form's values; `employeeId` '' is "no employee". */
export type AccountValues = {
  username: string;
  active: boolean;
  support: boolean;
  roleIds: string[];
  employeeId: string;
};

/** A support account is never linked to an employee (D83), so it never sends one. */
function employeeLink({ employeeId, support }: AccountValues) {
  return employeeId && !support ? { employeeId } : {};
}

/** "Nova conta" (`UserCreateRequest`): its username, roles, kind and, optionally, the employee it acts as. */
export function toCreateRequest(values: AccountValues): UserCreateRequest {
  return { username: values.username, roleIds: values.roleIds, support: values.support, ...employeeLink(values) };
}

/** "Editar" (`UserUpdateRequest`): replaces the account's state, roles and link; no employee unlinks it. */
export function toUpdateRequest(values: AccountValues, version: number): UserUpdateRequest {
  return { active: values.active, roleIds: values.roleIds, ...employeeLink(values), version };
}
