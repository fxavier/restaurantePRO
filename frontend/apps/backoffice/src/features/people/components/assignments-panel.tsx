import { zodResolver } from '@hookform/resolvers/zod';
import { Button, DataState, Form } from '@restaurantpro/ui';
import { X } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { isStepUpRequired } from '../../../lib/step-up';
import { useStoreAndRoleChoices } from '../hooks/use-choices';
import { useFormCommand } from '../hooks/use-form-command';
import { useAddAssignment, useRemoveAssignment } from '../hooks/use-people-commands';
import { useAssignments } from '../hooks/use-people-queries';
import { usePeopleStepUp } from '../hooks/use-people-step-up';
import { assignmentSchema, type AssignmentValues } from '../schemas';
import type { EmployeeSummary } from '../types';
import { CommandError } from './command-error';
import { FormFooter } from './form-footer';
import { StoreAndRoleFields } from './form-fields';

/**
 * An employee's store assignments (§5, `EmployeeStoreAssignment`): the stores where they open a POS session, each with
 * its role. The list is the server's, refetched after each change.
 */
export function AssignmentsPanel({ employee, onClose }: { employee: EmployeeSummary; onClose: () => void }) {
  const employeeId = employee.id ?? '';
  const assignments = useAssignments(employeeId);
  const { stores, roles } = useStoreAndRoleChoices();
  const storeName = stores.nameOf;
  const roleName = roles.nameOf;
  const remove = useRemoveAssignment(employeeId);
  const stepUp = usePeopleStepUp();
  const removeOne = (assignmentId: string) =>
    remove.mutate(assignmentId, { onError: (error) => isStepUpRequired(error) && stepUp.prompt(error) });

  return (
    <div className="flex flex-col gap-4">
      <DataState
        state={assignments.isPending ? 'loading' : assignments.isError ? 'error' : assignments.data.length ? 'ready' : 'empty'}
        emptyMessage="Sem lojas: o empregado ainda não abre sessão em nenhuma loja."
        onRetry={() => void assignments.refetch()}
      >
        <ul aria-label="Atribuições" className="flex flex-col divide-y rounded-md border">
          {assignments.data?.map((assignment) => {
            const label = `${roleName(assignment.roleId)} em ${storeName(assignment.storeId)}`;
            return (
              <li key={assignment.id} className="flex items-center justify-between gap-2 px-3 py-2">
                <span>
                  {storeName(assignment.storeId)} · {roleName(assignment.roleId)}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Retirar ${label}`}
                  disabled={remove.isPending}
                  onClick={() => removeOne(assignment.id ?? '')}
                >
                  <X aria-hidden="true" />
                  Retirar
                </Button>
              </li>
            );
          })}
        </ul>
      </DataState>
      <CommandError error={remove.error} pending={remove.isPending} onRetry={() => remove.variables && removeOne(remove.variables)} />
      <AddAssignmentForm employeeId={employeeId} onClose={onClose} />
      {stepUp.dialog}
    </div>
  );
}

function AddAssignmentForm({ employeeId, onClose }: { employeeId: string; onClose: () => void }) {
  const add = useAddAssignment(employeeId);
  const form = useForm<AssignmentValues>({ resolver: zodResolver(assignmentSchema), defaultValues: { storeId: '', roleId: '' } });
  const command = useFormCommand(form, add, { onDone: () => form.reset() });
  const onSubmit = form.handleSubmit((values) => command.send(values));

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4 border-t pt-4">
        <StoreAndRoleFields control={form.control} />
        <FormFooter
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={add.isPending}
          onRetry={() => add.variables && command.send(add.variables)}
          onCancel={onClose}
          cancelLabel="Fechar"
          submitLabel="Atribuir"
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}
