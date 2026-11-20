import { zodResolver } from '@hookform/resolvers/zod';
import { Form, FormControl, FormField, FormItem, FormLabel } from '@restaurantpro/ui';
import { useForm, type Control } from 'react-hook-form';
import type { AccountValues } from '../account-requests';
import { useStoreAndRoleChoices } from '../hooks/use-choices';
import { useFormCommand } from '../hooks/use-form-command';
import { useLoadedVersion } from '../hooks/use-loaded-version';
import { useSaveAccount } from '../hooks/use-people-commands';
import { useEmployeeChoices } from '../hooks/use-people-queries';
import { accountSchema } from '../schemas';
import type { UserSummary } from '../types';
import { CheckboxGroup } from './checkbox-group';
import { FormFooter } from './form-footer';
import { SelectField, TextField } from './form-fields';

type AccountFormProps = {
  /** The account being edited, as the list holds it now; none for a new one. */
  account?: UserSummary;
  onClose: () => void;
};

function valuesOf(account: UserSummary | undefined): AccountValues {
  return {
    username: account?.username ?? '',
    active: account?.active ?? true,
    support: account?.support ?? false,
    roleIds: account?.roleIds ?? [],
    employeeId: account?.employeeId ?? '',
  };
}

/**
 * A BackOffice account (§5, D175: Keycloak holds its credentials): its username, the roles it holds in every store,
 * and optionally the employee whose store roles it also holds. A support account is fixed as such and never linked.
 */
export function AccountForm({ account, onClose }: AccountFormProps) {
  const save = useSaveAccount();
  const roles = useStoreAndRoleChoices();
  const employees = useEmployeeChoices();
  const form = useForm<AccountValues>({ resolver: zodResolver(accountSchema), defaultValues: valuesOf(account) });
  const loaded = useLoadedVersion(form, save, account, () => valuesOf(account));
  const command = useFormCommand(form, save, {
    conflicts: { USERNAME_TAKEN: 'username', EMPLOYEE_ACCOUNT_TAKEN: 'employeeId' },
    onDone: onClose,
  });
  const onSubmit = form.handleSubmit((values) => command.send({ userId: account?.id, values, version: loaded.version }));
  const support = form.watch('support');

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="username" label="Nome de utilizador" maxLength={254} disabled={!!account} />
        {account ? (
          <Checkbox control={form.control} name="active" label="Conta ativa" />
        ) : (
          <Checkbox control={form.control} name="support" label="Conta de suporte (só entra durante um acesso de suporte)" />
        )}
        <FormField
          control={form.control}
          name="roleIds"
          render={({ field }) => (
            <CheckboxGroup
              legend="Funções"
              choices={roles.roleChoices}
              value={field.value}
              onChange={field.onChange}
              emptyMessage={roles.rolesPending ? 'A carregar…' : 'Ainda não há funções.'}
            />
          )}
        />
        {!support && (
          <SelectField
            control={form.control}
            name="employeeId"
            label="Empregado"
            placeholder="Nenhum"
            choices={(employees.data ?? []).map((employee) => ({ value: employee.id ?? '', label: employee.name ?? '' }))}
          />
        )}
        <FormFooter
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={save.isPending}
          onRetry={() => save.variables && command.send(save.variables)}
          onCancel={onClose}
          submitLabel={account ? 'Guardar' : 'Criar conta'}
          onLoadCurrent={account && loaded.loadCurrent}
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}

type CheckboxProps = { control: Control<AccountValues>; name: 'active' | 'support'; label: string };

function Checkbox({ control, name, label }: CheckboxProps) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem className="flex flex-row items-center gap-2">
          <FormControl>
            <input
              type="checkbox"
              className="size-4 accent-primary"
              checked={field.value}
              onChange={(event) => field.onChange(event.target.checked)}
              onBlur={field.onBlur}
              ref={field.ref}
            />
          </FormControl>
          <FormLabel className="font-normal">{label}</FormLabel>
        </FormItem>
      )}
    />
  );
}
