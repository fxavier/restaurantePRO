import {
  Button,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { KeyRound, LockOpen, UserPlus } from 'lucide-react';
import { useState } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import { useStoreSelection } from '../../organization';
import { useUnlockEmployee } from '../hooks/use-people-commands';
import { useEmployees } from '../hooks/use-people-queries';
import { usePeopleStepUp } from '../hooks/use-people-step-up';
import type { EmployeeSummary } from '../types';
import { CommandError } from './command-error';
import { FormDialog } from './form-dialog';
import { employeeState, employeeStatuses } from './labels';
import { ListCard } from './list-card';
import { NewEmployeeForm } from './new-employee-form';
import { ResetPinForm } from './reset-pin-form';

type Open = { kind: 'new' } | { kind: 'reset-pin'; employee: EmployeeSummary } | null;

/** "Empregados" (§5): who works in the company, or in the store chosen in the top bar, and what can be done to each. */
export function EmployeesTab() {
  const { storeId } = useStoreSelection();
  const [page, setPage] = useState(0);
  const employees = useEmployees(storeId, page);
  const unlock = useUnlockEmployee();
  const stepUp = usePeopleStepUp();
  const [open, setOpen] = useState<Open>(null);
  const [done, setDone] = useState('');
  const close = () => setOpen(null);

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
          <Button onClick={() => setOpen({ kind: 'new' })}>
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
          <Table>
            <TableCaption className="sr-only">Empregados</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Nome</TableHead>
                <TableHead>Código</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((employee) => (
                <TableRow key={employee.id}>
                  <TableCell className="font-medium">{employee.name}</TableCell>
                  <TableCell className="font-mono text-muted-foreground">{employee.code}</TableCell>
                  <TableCell>
                    <StatusBadge status={employeeState(employee)} statuses={employeeStatuses} />
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      {employee.locked && (
                        <Button
                          variant="outline"
                          size="sm"
                          aria-label={`Desbloquear ${employee.name}`}
                          disabled={unlock.isPending}
                          onClick={() => unlockOne(employee)}
                        >
                          <LockOpen aria-hidden="true" />
                          Desbloquear
                        </Button>
                      )}
                      <Button
                        variant="outline"
                        size="sm"
                        aria-label={`Repor PIN de ${employee.name}`}
                        onClick={() => setOpen({ kind: 'reset-pin', employee })}
                      >
                        <KeyRound aria-hidden="true" />
                        Repor PIN
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </ListCard>
      <FormDialog
        title="Novo empregado"
        description="O empregado abre sessão no POS com o código e o PIN, nas lojas onde tem uma função."
        open={open?.kind === 'new'}
        onClose={close}
      >
        <NewEmployeeForm onClose={close} />
      </FormDialog>
      <FormDialog
        title={open?.kind === 'reset-pin' ? `Repor o PIN de ${open.employee.name}` : ''}
        description="No próximo início de sessão no POS, o empregado escolhe um PIN só seu."
        open={open?.kind === 'reset-pin'}
        onClose={close}
      >
        {open?.kind === 'reset-pin' && (
          <ResetPinForm
            employee={open.employee}
            onClose={close}
            onDone={() => {
              setDone(`PIN de ${open.employee.name} reposto.`);
              close();
            }}
          />
        )}
      </FormDialog>
      {stepUp.dialog}
    </div>
  );
}
