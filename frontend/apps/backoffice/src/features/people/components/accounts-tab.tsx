import { Button, StatusBadge, StatusPill, TableCell, TableRow } from '@restaurantpro/ui';
import { LifeBuoy, Pencil, UserPlus, UserRound } from 'lucide-react';
import { useState } from 'react';
import { useStoreAndRoleChoices } from '../hooks/use-choices';
import { useEditing } from '../hooks/use-editing';
import { useUsers } from '../hooks/use-people-queries';
import type { UserSummary } from '../types';
import { AccountForm } from './account-form';
import { FormDialog } from './form-dialog';
import { accountStatuses } from './labels';
import { ListCard } from './list-card';
import { ListTable } from './list-table';

/** "Utilizadores" (§5): the BackOffice accounts, their roles in every store, and whether each is a support account. */
export function AccountsTab() {
  const [page, setPage] = useState(0);
  const users = useUsers(page);
  // Started with the list, so the roles' names are there when the rows are.
  const roleName = useStoreAndRoleChoices().roles.nameOf;
  const { creating, startCreating, stopCreating, editing, startEditing, stopEditing } = useEditing(users.data?.rows);

  return (
    <>
      <ListCard
        title="Contas do BackOffice"
        description="Quem entra no BackOffice. A palavra-passe e o segundo fator são geridos no início de sessão."
        actions={
          <Button onClick={startCreating}>
            <UserPlus aria-hidden="true" />
            Nova conta
          </Button>
        }
        query={users}
        what="as contas"
        emptyMessage="Ainda não há contas."
        onPage={setPage}
      >
        {(rows) => (
          <ListTable caption="Contas do BackOffice" headers={['Utilizador', 'Funções', 'Tipo', 'Estado']}>
            {rows.map((user) => (
              <AccountRow key={user.id} account={user} roleName={roleName} onEdit={() => startEditing(user)} />
            ))}
          </ListTable>
        )}
      </ListCard>
      <FormDialog
        title="Nova conta"
        description="A pessoa define a palavra-passe e o segundo fator no primeiro início de sessão."
        open={creating}
        onClose={stopCreating}
      >
        <AccountForm onClose={stopCreating} />
      </FormDialog>
      <FormDialog
        title={`Editar a conta ${editing?.username}`}
        description="As funções valem em todas as lojas; as do empregado ligado valem nas lojas dele."
        open={editing !== undefined}
        onClose={stopEditing}
      >
        <AccountForm account={editing} onClose={stopEditing} />
      </FormDialog>
    </>
  );
}

/** One account: username, the names of its roles, support or not, active or not. */
function AccountRow({
  account,
  roleName,
  onEdit,
}: {
  account: UserSummary;
  roleName: (id: string | undefined) => string;
  onEdit: () => void;
}) {
  const roleIds = account.roleIds ?? [];
  return (
    <TableRow>
      <TableCell className="font-mono">{account.username}</TableCell>
      <TableCell className="whitespace-normal">{roleIds.length ? roleIds.map(roleName).join(', ') : '—'}</TableCell>
      <TableCell>
        <StatusPill tone={account.support ? 'info' : 'neutral'} icon={account.support ? LifeBuoy : UserRound}>
          {account.support ? 'Suporte' : 'Conta'}
        </StatusPill>
      </TableCell>
      <TableCell>
        <StatusBadge status={account.active === false ? 'INACTIVE' : 'ACTIVE'} statuses={accountStatuses} look="tint" />
      </TableCell>
      <TableCell>
        <div className="flex justify-end">
          <Button variant="outline" size="sm" aria-label={`Editar ${account.username}`} onClick={onEdit}>
            <Pencil aria-hidden="true" />
            Editar
          </Button>
        </div>
      </TableCell>
    </TableRow>
  );
}
