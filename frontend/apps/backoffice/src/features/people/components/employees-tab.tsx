import {
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { useState } from 'react';
import { useStoreSelection } from '../../organization';
import { useEmployees } from '../hooks/use-people-queries';
import { employeeState, employeeStatuses } from './labels';
import { ListCard } from './list-card';

/** "Empregados" (§5): who works in the company, or in the store chosen in the top bar, and what can be done to each. */
export function EmployeesTab() {
  const { storeId } = useStoreSelection();
  const [page, setPage] = useState(0);
  const employees = useEmployees(storeId, page);

  return (
    <ListCard
      title="Empregados"
      description="Quem abre sessão nos terminais POS com o seu código e PIN."
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
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </ListCard>
  );
}
