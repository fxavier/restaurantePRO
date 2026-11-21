import {
  Badge,
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
import { Pencil, UserPlus } from 'lucide-react';
import { useState } from 'react';
import { useStoreAndRoleChoices } from '../hooks/use-choices';
import { useUsers } from '../hooks/use-people-queries';
import type { UserSummary } from '../types';
import { AccountForm } from './account-form';
import { FormDialog } from './form-dialog';
import { accountStatuses } from './labels';
import { ListCard } from './list-card';

/** "Utilizadores" (§5): the BackOffice accounts, their roles in every store, and whether each is a support account. */
export function AccountsTab() {
  const [page, setPage] = useState(0);
  const users = useUsers(page);
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  // The account as the list holds it now, so a conflict's refetch reaches the open form.
  const editing = users.data?.rows.find((user) => user.id === editingId);

  return (
    <>
      <ListCard
        title="Contas do BackOffice"
        description="Quem entra no BackOffice. A palavra-passe e o segundo fator são geridos no início de sessão."
        actions={
          <Button onClick={() => setCreating(true)}>
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
          <Table>
            <TableCaption className="sr-only">Contas do BackOffice</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Utilizador</TableHead>
                <TableHead>Funções</TableHead>
                <TableHead>Tipo</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((user) => (
                <AccountRow key={user.id} account={user} onEdit={() => setEditingId(user.id ?? '')} />
              ))}
            </TableBody>
          </Table>
        )}
      </ListCard>
      <FormDialog
        title="Nova conta"
        description="A pessoa define a palavra-passe e o segundo fator no primeiro início de sessão."
        open={creating}
        onClose={() => setCreating(false)}
      >
        <AccountForm onClose={() => setCreating(false)} />
      </FormDialog>
      <FormDialog
        title={`Editar a conta ${editing?.username}`}
        description="As funções valem em todas as lojas; as do empregado ligado valem nas lojas dele."
        open={editing !== undefined}
        onClose={() => setEditingId(null)}
      >
        <AccountForm account={editing} onClose={() => setEditingId(null)} />
      </FormDialog>
    </>
  );
}

/** One account: username, the names of its roles, support or not, active or not. */
function AccountRow({ account, onEdit }: { account: UserSummary; onEdit: () => void }) {
  const { roleName } = useStoreAndRoleChoices();
  const roleIds = account.roleIds ?? [];
  return (
    <TableRow>
      <TableCell className="font-mono">{account.username}</TableCell>
      <TableCell className="whitespace-normal">{roleIds.length ? roleIds.map(roleName).join(', ') : '—'}</TableCell>
      <TableCell>
        <Badge variant={account.support ? 'info' : 'secondary'}>{account.support ? 'Suporte' : 'Conta'}</Badge>
      </TableCell>
      <TableCell>
        <StatusBadge status={account.active === false ? 'INACTIVE' : 'ACTIVE'} statuses={accountStatuses} />
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
