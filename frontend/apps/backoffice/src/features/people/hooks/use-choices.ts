import { useEmployeeChoices, useRoleChoices, useStoreChoices, useUsers } from './use-people-queries';

export type Choice = { value: string; label: string };

function choicesOf(items: readonly { id?: string; name?: string }[] | undefined): Choice[] {
  return (items ?? []).map(({ id, name }) => ({ value: id ?? '', label: name ?? '' }));
}

/**
 * The support accounts, to name each grant's account and to choose one; the first page of accounts holds a company's
 * few support accounts (simplification, no paging).
 */
export function useSupportAccounts() {
  const accounts = useUsers(0).data?.rows ?? [];
  return {
    supportAccounts: accounts.filter((account) => account.support),
    usernameOf: (id: string | undefined) => accounts.find((account) => account.id === id)?.username ?? id ?? '',
  };
}

/** The employees an account can be linked to, as picker choices. */
export function useEmployeeChoiceList(): Choice[] {
  return choicesOf(useEmployeeChoices().data);
}

/** The stores and roles as picker choices, and their names by id (an unknown id shows as itself). */
export function useStoreAndRoleChoices() {
  const stores = useStoreChoices();
  const roles = useRoleChoices();
  const storeChoices = choicesOf(stores.data);
  const roleChoices = choicesOf(roles.data);
  const nameIn = (choices: Choice[]) => (id: string | undefined) =>
    choices.find((choice) => choice.value === id)?.label ?? id ?? '';
  return {
    storeChoices,
    roleChoices,
    storesPending: stores.isPending,
    rolesPending: roles.isPending,
    storeName: nameIn(storeChoices),
    roleName: nameIn(roleChoices),
  };
}
