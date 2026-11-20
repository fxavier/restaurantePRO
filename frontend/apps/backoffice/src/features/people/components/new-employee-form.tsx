import { zodResolver } from '@hookform/resolvers/zod';
import { Form } from '@restaurantpro/ui';
import { useForm } from 'react-hook-form';
import { useStoreSelection } from '../../organization';
import { useHireEmployee } from '../hooks/use-people-commands';
import { useFormCommand } from '../hooks/use-form-command';
import { newEmployeeSchema, type NewEmployeeValues } from '../schemas';
import { FormFooter } from './form-footer';
import { StoreAndRoleFields, TextField } from './form-fields';

/**
 * "Novo empregado" (§5): code, name, initial PIN, and the first store and role, which the contract hires with. The
 * store starts as the one chosen in the top bar. The PIN lives only in this form while it is open.
 */
export function NewEmployeeForm({ onClose }: { onClose: () => void }) {
  const { storeId } = useStoreSelection();
  const hire = useHireEmployee();
  const form = useForm<NewEmployeeValues>({
    resolver: zodResolver(newEmployeeSchema),
    defaultValues: { code: '', name: '', pin: '', storeId: storeId ?? '', roleId: '' },
  });
  const command = useFormCommand(form, hire, { conflicts: { EMPLOYEE_CODE_TAKEN: 'code' }, onDone: onClose });
  const onSubmit = form.handleSubmit((values) => command.send(values));

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="code" label="Código" maxLength={20} className="max-w-48" />
        <TextField control={form.control} name="name" label="Nome" maxLength={120} />
        <TextField
          control={form.control}
          name="pin"
          label="PIN inicial"
          type="password"
          inputMode="numeric"
          autoComplete="new-password"
          maxLength={8}
          className="max-w-48"
        />
        <StoreAndRoleFields control={form.control} />
        <FormFooter
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={hire.isPending}
          onRetry={() => hire.variables && command.send(hire.variables)}
          onCancel={onClose}
          submitLabel="Criar empregado"
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}
