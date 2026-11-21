import { Button, StatusBadge, TableCell, TableRow } from '@restaurantpro/ui';
import { KeyRound, LockOpen, Pencil, Store, UserPlus } from 'lucide-react';
import { useState } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import { useStoreSelection } from '../../organization';
import { useEditing } from '../hooks/use-editing';
import { useUnlockEmployee } from '../hooks/use-people-commands';
import { useEmployees } from '../hooks/use-people-queries';
import { usePeopleStepUp } from '../hooks/use-people-step-up';
import type { EmployeeSummary } from '../types';
import { AssignmentsPanel } from './assignments-panel';
import { CommandError } from './command-error';
import { EmployeeForm } from './employee-form';
import { FormDialog } from './form-dialog';
import { employeeState, employeeStatuses } from './labels';
import { ListCard } from './list-card';
import { ListTable } from './list-table';
import { NewEmployeeForm } from './new-employee-form';
import { ResetPinForm } from './reset-pin-form';

/** "Empregados" (§5): who works in the company, or in the store chosen in the top bar, and what can be done to each. */
export function EmployeesTab() {
  const { storeId } = useStoreSelection();
  const [page, setPage] = useState(0);
  const employees = useEmployees(storeId, page);
  const unlock = useUnlockEmployee();
  const stepUp = usePeopleStepUp();
  const { creating, startCreating, stopCreating, editing, startEditing, stopEditing } = useEditing(employees.data?.rows);
  const [resetting, setResetting] = useState<EmployeeSummary | null>(null);
  const [assigning, setAssigning] = useState<EmployeeSummary | null>(null);
  const [done, setDone] = useState('');

  const unlockOne = (employee: EmployeeSummary) =>
    unlock.mutate(employee, {
      onSuccess: () => setDone(`${employee.name} desbloqueado.`),
      onError: (error) => isStepUpRequired(error) && stepUp.prompt(error),
    });

  return (
    <div className="flex flex-col gap-4">
      <p role="status" className="sr-only">
        {done}
      </p>
      <CommandError error={unlock.error} pending={unlock.isPending} onRetry={() => unlock.variables && unlockOne(unlock.variables)} />
      <ListCard
        title="Empregados"
        description="Quem abre sessão nos terminais POS com o seu código e PIN."
        actions={
          <Button onClick={startCreating}>
            <UserPlus aria-hidden="true" />
            Novo empregado
          </Button>
        }
        query={employees}
        what="os empregados"
        emptyMessage="Ainda não há empregados."
        onPage={setPage}
      >
        {(rows) => (
          <ListTable caption="Empregados" headers={['Nome', 'Código', 'Estado']}>
            {rows.map((employee) => (
              <EmployeeRow
                key={employee.id}
                employee={employee}
                unlocking={unlock.isPending}
                onEdit={() => startEditing(employee)}
                onUnlock={() => unlockOne(employee)}
                onAssignments={() => setAssigning(employee)}
                onResetPin={() => setResetting(employee)}
              />
            ))}
          </ListTable>
        )}
      </ListCard>
      <FormDialog
        title="Novo empregado"
        description="O empregado abre sessão no POS com o código e o PIN, nas lojas onde tem uma função."
        open={creating}
        onClose={stopCreating}
      >
        <NewEmployeeForm onClose={stopCreating} />
      </FormDialog>
      <FormDialog
        title={`Editar ${editing?.name}`}
        description="Um empregado inativo deixa de abrir sessão no POS em todas as lojas."
        open={editing !== undefined}
        onClose={stopEditing}
      >
        {editing && <EmployeeForm employee={editing} onClose={stopEditing} />}
      </FormDialog>
      <FormDialog
        title={`Repor o PIN de ${resetting?.name}`}
        description="No próximo início de sessão no POS, o empregado escolhe um PIN só seu."
        open={resetting !== null}
        onClose={() => setResetting(null)}
      >
        <ResetPinForm
          employee={resetting ?? {}}
          onClose={() => setResetting(null)}
          onDone={() => {
            setDone(`PIN de ${resetting?.name} reposto.`);
            setResetting(null);
          }}
        />
      </FormDialog>
      <FormDialog
        title={`Lojas e funções de ${assigning?.name}`}
        description="O empregado abre sessão no POS das lojas onde tem uma função, com as permissões dessa função."
        open={assigning !== null}
        onClose={() => setAssigning(null)}
      >
        <AssignmentsPanel employee={assigning ?? {}} onClose={() => setAssigning(null)} />
      </FormDialog>
      {stepUp.dialog}
    </div>
  );
}

type EmployeeRowProps = {
  employee: EmployeeSummary;
  unlocking: boolean;
  onEdit: () => void;
  onUnlock: () => void;
  onAssignments: () => void;
  onResetPin: () => void;
};

/** One employee: name, code, state, and the commands; "Desbloquear" only while the PIN is locked. */
function EmployeeRow({ employee, unlocking, onEdit, onUnlock, onAssignments, onResetPin }: EmployeeRowProps) {
  return (
    <TableRow>
      <TableCell className="font-medium">{employee.name}</TableCell>
      <TableCell className="font-mono text-muted-foreground">{employee.code}</TableCell>
      <TableCell>
        <StatusBadge status={employeeState(employee)} statuses={employeeStatuses} />
      </TableCell>
      <TableCell>
        <div className="flex justify-end gap-2">
          {employee.locked && (
            <Button variant="outline" size="sm" aria-label={`Desbloquear ${employee.name}`} disabled={unlocking} onClick={onUnlock}>
              <LockOpen aria-hidden="true" />
              Desbloquear
            </Button>
          )}
          <Button variant="outline" size="sm" aria-label={`Editar ${employee.name}`} onClick={onEdit}>
            <Pencil aria-hidden="true" />
            Editar
          </Button>
          <Button variant="outline" size="sm" aria-label={`Lojas e funções de ${employee.name}`} onClick={onAssignments}>
            <Store aria-hidden="true" />
            Lojas
          </Button>
          <Button variant="outline" size="sm" aria-label={`Repor PIN de ${employee.name}`} onClick={onResetPin}>
            <KeyRound aria-hidden="true" />
            Repor PIN
          </Button>
        </div>
      </TableCell>
    </TableRow>
  );
}
