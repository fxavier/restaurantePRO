import {
  Badge,
  Button,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Pencil, ShieldPlus } from 'lucide-react';
import { useState } from 'react';
import { useRoles } from '../hooks/use-people-queries';
import { FormDialog } from './form-dialog';
import { ListCard } from './list-card';
import { RoleForm } from './role-form';

type Open = { kind: 'new' } | { kind: 'edit'; roleId: string } | null;

/** "Funções" (§5, RBAC): each role and the permissions it grants wherever it is assigned. */
export function RolesTab() {
  const [page, setPage] = useState(0);
  const roles = useRoles(page);
  const [open, setOpen] = useState<Open>(null);
  const close = () => setOpen(null);
  // The role as the list holds it now, so a conflict's refetch reaches the open form.
  const editing = open?.kind === 'edit' ? roles.data?.rows.find((role) => role.id === open.roleId) : undefined;

  return (
    <>
      <ListCard
        title="Funções"
        description="Cada função concede permissões em todas as lojas onde é atribuída."
        actions={
          <Button onClick={() => setOpen({ kind: 'new' })}>
            <ShieldPlus aria-hidden="true" />
            Nova função
          </Button>
        }
        query={roles}
        what="as funções"
        emptyMessage="Ainda não há funções."
        onPage={setPage}
      >
        {(rows) => (
          <Table>
            <TableCaption className="sr-only">Funções</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Nome</TableHead>
                <TableHead>Permissões</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((role) => (
                <TableRow key={role.id}>
                  <TableCell className="font-medium">{role.name}</TableCell>
                  <TableCell className="whitespace-normal">
                    <span className="sr-only">{role.permissions?.length ?? 0} permissões: </span>
                    <span className="flex flex-wrap gap-1">
                      {role.permissions?.map((permission) => (
                        <Badge key={permission} variant="secondary" className="font-mono normal-case">
                          {permission}
                        </Badge>
                      ))}
                    </span>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button
                        variant="outline"
                        size="sm"
                        aria-label={`Editar ${role.name}`}
                        onClick={() => setOpen({ kind: 'edit', roleId: role.id ?? '' })}
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
        title="Nova função"
        description="Dê-lhe um nome e as permissões que concede. Atribua-a depois a empregados ou a contas."
        open={open?.kind === 'new'}
        onClose={close}
      >
        <RoleForm onClose={close} />
      </FormDialog>
      <FormDialog
        title={`Editar a função ${editing?.name ?? ''}`}
        description="As alterações valem de imediato para todos os que têm esta função."
        open={open?.kind === 'edit' && editing !== undefined}
        onClose={close}
      >
        <RoleForm role={editing} onClose={close} />
      </FormDialog>
    </>
  );
}
