import { zodResolver } from '@hookform/resolvers/zod';
import { Form } from '@restaurantpro/ui';
import { useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useLoadedVersion } from '../hooks/use-loaded-version';
import { useUpdateEmployee } from '../hooks/use-people-commands';
import { employeeSchema, type EmployeeValues } from '../schemas';
import type { EmployeeSummary } from '../types';
import { FormFooter } from './form-footer';
import { CheckboxField, TextField } from './form-fields';

type EmployeeFormProps = {
  /** The employee being edited, as the list holds it now (refetched after a conflict). */
  employee: EmployeeSummary;
  onClose: () => void;
};

function valuesOf({ name = '', active = true }: EmployeeSummary): EmployeeValues {
  return { name, active };
}

/**
 * "Editar" an employee (§5): the name, and "Ativo"; an inactive employee no longer opens a POS session in any store,
 * whatever their assignments. The code is fixed. A conflicting edit offers the current version, then saves against it.
 */
export function EmployeeForm({ employee, onClose }: EmployeeFormProps) {
  const update = useUpdateEmployee();
  const form = useForm<EmployeeValues>({ resolver: zodResolver(employeeSchema), defaultValues: valuesOf(employee) });
  const loaded = useLoadedVersion(form, update, employee, () => valuesOf(employee));
  const command = useFormCommand(form, update, { onDone: onClose });
  const onSubmit = form.handleSubmit((values) =>
    command.send({ employeeId: employee.id ?? '', body: { ...values, version: loaded.version } }),
  );

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="name" label="Nome" maxLength={120} />
        <CheckboxField control={form.control} name="active" label="Ativo" />
        <FormFooter
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={update.isPending}
          onRetry={() => update.variables && command.send(update.variables)}
          onCancel={onClose}
          submitLabel="Guardar"
          onLoadCurrent={loaded.loadCurrent}
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}
