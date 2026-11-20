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
import { AccountForm } from './account-form';
import { FormDialog } from './form-dialog';
import { accountStatuses } from './labels';
import { ListCard } from './list-card';

type Open = { kind: 'new' } | { kind: 'edit'; userId: string } | null;

/** "Utilizadores" (§5): the BackOffice accounts, their roles in every store, and whether each is a support account. */
export function AccountsTab() {
  const [page, setPage] = useState(0);
  const users = useUsers(page);
  const { roleName } = useStoreAndRoleChoices();
  const [open, setOpen] = useState<Open>(null);
  const close = () => setOpen(null);
  // The account as the list holds it now, so a conflict's refetch reaches the open form.
  const editing = open?.kind === 'edit' ? users.data?.rows.find((user) => user.id === open.userId) : undefined;

  return (
    <>
      <ListCard
        title="Contas do BackOffice"
        description="Quem entra no BackOffice. A palavra-passe e o segundo fator são geridos no início de sessão."
        actions={
          <Button onClick={() => setOpen({ kind: 'new' })}>
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
                <TableRow key={user.id}>
                  <TableCell className="font-mono">{user.username}</TableCell>
                  <TableCell className="whitespace-normal">
                    {user.roleIds?.length ? user.roleIds.map(roleName).join(', ') : '—'}
                  </TableCell>
                  <TableCell>
                    <Badge variant={user.support ? 'info' : 'secondary'}>{user.support ? 'Suporte' : 'Conta'}</Badge>
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={user.active === false ? 'INACTIVE' : 'ACTIVE'} statuses={accountStatuses} />
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button
                        variant="outline"
                        size="sm"
                        aria-label={`Editar ${user.username}`}
                        onClick={() => setOpen({ kind: 'edit', userId: user.id ?? '' })}
                      >
                        <Pencil aria-hidden="true" />
                        Editar
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
        title="Nova conta"
        description="A pessoa recebe o convite para definir a palavra-passe e o segundo fator no início de sessão."
        open={open?.kind === 'new'}
        onClose={close}
      >
        <AccountForm onClose={close} />
      </FormDialog>
      <FormDialog
        title={`Editar a conta ${editing?.username ?? ''}`}
        description="As funções valem em todas as lojas; as do empregado ligado valem nas lojas dele."
        open={open?.kind === 'edit' && editing !== undefined}
        onClose={close}
      >
        <AccountForm account={editing} onClose={close} />
      </FormDialog>
    </>
  );
}
