import { LayoutDashboard, MonitorSmartphone, type LucideIcon } from 'lucide-react';

export type Area = { label: string; to: string; icon: LucideIcon; end?: boolean; permission?: string };
export type AreaGroup = { label?: string; areas: Area[] };

/**
 * The §5 areas in the sidebar; each shows only with its permission (story 8). Names are the backend's
 * `access.Permission` constants. R1 has one area.
 */
export const areaGroups: AreaGroup[] = [
  { areas: [{ label: 'Visão geral', to: '/', icon: LayoutDashboard, end: true }] },
  {
    label: 'Organização',
    areas: [{ label: 'Terminais', to: '/organization/terminals', icon: MonitorSmartphone, permission: 'TERMINAL_MANAGE' }],
  },
];

/** The groups and areas a user with these permissions may see; a group with no visible area disappears. */
export function visibleAreas(permissions: readonly string[]): AreaGroup[] {
  return areaGroups
    .map((group) => ({
      ...group,
      areas: group.areas.filter((area) => !area.permission || holds(permissions, area.permission)),
    }))
    .filter((group) => group.areas.length > 0);
}

/**
 * `SessionView.permissions` are the backend's authorities: `PERM_<NAME>` company-wide or `PERM_<NAME>@<storeId>` in
 * one store. An area shows with the permission anywhere; the API decides per store.
 */
function holds(permissions: readonly string[], name: string): boolean {
  const authority = `PERM_${name}`;
  return permissions.some((permission) => permission === authority || permission.startsWith(`${authority}@`));
}
