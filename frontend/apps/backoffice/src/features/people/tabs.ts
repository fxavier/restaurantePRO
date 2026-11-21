/** The people screen's tabs, in Stitch's order, each with the permission its list endpoint requires. */
export const PEOPLE_TABS = [
  { id: 'empregados', label: 'Empregados', permission: 'EMPLOYEE_MANAGE' },
  { id: 'funcoes', label: 'Funções', permission: 'ROLE_MANAGE' },
  { id: 'utilizadores', label: 'Utilizadores', permission: 'USER_MANAGE' },
  { id: 'suporte', label: 'Acessos de suporte', permission: 'SUPPORT_ACCESS_MANAGE' },
] as const;

export type PeopleTab = (typeof PEOPLE_TABS)[number];
export type PeopleTabId = PeopleTab['id'];

/**
 * The tabs a user with these `SessionView.permissions` may open: the backend's authorities are `PERM_<NAME>`
 * company-wide or `PERM_<NAME>@<storeId>` in one store, and either shows the tab (the API decides per store).
 */
export function visibleTabs(permissions: readonly string[]): PeopleTab[] {
  return PEOPLE_TABS.filter(({ permission }) => {
    const authority = `PERM_${permission}`;
    return permissions.some((held) => held === authority || held.startsWith(`${authority}@`));
  });
}

/** The tab `?tab=` names when the user may open it, otherwise the first one they may; none without any. */
export function selectedTab(param: string | null, visible: readonly PeopleTab[]): PeopleTabId | undefined {
  return (visible.find((tab) => tab.id === param) ?? visible[0])?.id;
}
