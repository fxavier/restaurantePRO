import { useRoleChoices, useStoreChoices } from './use-people-queries';

export type Choice = { value: string; label: string };

function choicesOf(items: readonly { id?: string; name?: string }[] | undefined): Choice[] {
  return (items ?? []).map(({ id, name }) => ({ value: id ?? '', label: name ?? '' }));
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
