import type { UseQueryResult } from '@tanstack/react-query';
import { useAccountChoices, useEmployeeChoices, useRoleChoices, useStoreChoices } from './use-people-queries';

export type Choice = { value: string; label: string };

/** One picker's choices as the server answered them: still loading, refused or unreachable, or there. */
export type ChoiceList = {
  /** What it lists, for its refusal: "Sem permissão para ver {what}." */
  what: string;
  choices: Choice[];
  pending: boolean;
  error: Error | null;
  retry: () => void;
  /** The label of an id; an id the list does not hold (or a list that failed) shows as itself. */
  nameOf: (id: string | undefined) => string;
};

function choiceList<Item>(
  what: string,
  query: UseQueryResult<Item[]>,
  toChoice: (item: Item) => Choice,
  keep: (item: Item) => boolean = () => true,
): ChoiceList {
  const all = (query.data ?? []).map(toChoice);
  return {
    what,
    choices: (query.data ?? []).filter(keep).map(toChoice),
    pending: query.isPending,
    error: query.error,
    retry: () => void query.refetch(),
    nameOf: (id) => all.find((choice) => choice.value === id)?.label ?? id ?? '',
  };
}

const named = ({ id, name }: { id?: string; name?: string }): Choice => ({ value: id ?? '', label: name ?? '' });

/** The support accounts to choose one from; `nameOf` names any account, as a grant's row does. */
export function useSupportAccounts(): ChoiceList {
  return choiceList(
    'as contas de suporte',
    useAccountChoices(),
    ({ id, username }) => ({ value: id ?? '', label: username ?? '' }),
    (account) => account.support === true,
  );
}

/** The employees an account can be linked to. */
export function useEmployeeChoiceList(): ChoiceList {
  return choiceList('os empregados', useEmployeeChoices(), named);
}

/** The stores and the roles, the pair an employee is hired or assigned with. */
export function useStoreAndRoleChoices(): { stores: ChoiceList; roles: ChoiceList } {
  return { stores: choiceList('as lojas', useStoreChoices(), named), roles: choiceList('as funções', useRoleChoices(), named) };
}
