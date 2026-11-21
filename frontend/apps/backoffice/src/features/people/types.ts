import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
type Dto<Name extends string> = Schemas[`com.xavier.backend.identity.model.dto.${Name}` & keyof Schemas];

export type EmployeeSummary = Dto<'EmployeeSummary'>;
export type EmployeeCreateRequest = Dto<'EmployeeCreateRequest'>;
export type EmployeeUpdateRequest = Dto<'EmployeeUpdateRequest'>;
export type AssignmentSummary = Dto<'AssignmentSummary'>;
export type AssignmentRequest = Dto<'AssignmentRequest'>;
export type RoleSummary = Dto<'RoleSummary'>;
export type RoleCreateRequest = Dto<'RoleCreateRequest'>;
export type RoleUpdateRequest = Dto<'RoleUpdateRequest'>;
export type UserSummary = Dto<'UserSummary'>;
export type UserCreateRequest = Dto<'UserCreateRequest'>;
export type UserUpdateRequest = Dto<'UserUpdateRequest'>;
export type SupportGrantSummary = Dto<'SupportGrantSummary'>;
export type SupportGrantRequest = Dto<'SupportGrantRequest'>;
export type StoreSummary = Schemas['com.xavier.backend.organization.model.dto.StoreSummary'];

/** One of the backend's `access.Permission` names, as the role contract lists them. */
export type Permission = NonNullable<RoleSummary['permissions']>[number];

/** One page of a list, as the screen shows it: the rows, the page asked for and how many rows there are in all. */
export type ListPage<Row> = { rows: Row[]; page: number; totalElements: number };
