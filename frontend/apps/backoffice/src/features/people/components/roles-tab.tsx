import { Badge, Button, TableCell, TableRow } from '@restaurantpro/ui';
import { Pencil, ShieldPlus } from 'lucide-react';
import { useState } from 'react';
import { useEditing } from '../hooks/use-editing';
import { useRoles } from '../hooks/use-people-queries';
import type { RoleSummary } from '../types';
import { FormDialog } from './form-dialog';
import { ListCard } from './list-card';
import { ListTable } from './list-table';
import { RoleForm } from './role-form';

/** "Funções" (§5, RBAC): each role and the permissions it grants wherever it is assigned. */
export function RolesTab() {
  const [page, setPage] = useState(0);
  const roles = useRoles(page);
  const { creating, startCreating, stopCreating, editing, startEditing, stopEditing } = useEditing(roles.data?.rows);

  return (
    <>
      <ListCard
        title="Funções"
        description="Cada função concede permissões em todas as lojas onde é atribuída."
        actions={
          <Button onClick={startCreating}>
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
          <ListTable caption="Funções" headers={['Nome', 'Permissões']}>
            {rows.map((role) => (
              <RoleRow key={role.id} role={role} onEdit={() => startEditing(role)} />
            ))}
          </ListTable>
        )}
      </ListCard>
      <FormDialog
        title="Nova função"
        description="Dê-lhe um nome e as permissões que concede. Atribua-a depois a empregados ou a contas."
        open={creating}
        onClose={stopCreating}
      >
        <RoleForm onClose={stopCreating} />
      </FormDialog>
      <FormDialog
        title={`Editar a função ${editing?.name}`}
        description="As alterações valem de imediato para todos os que têm esta função."
        open={editing !== undefined}
        onClose={stopEditing}
      >
        <RoleForm role={editing} onClose={stopEditing} />
      </FormDialog>
    </>
  );
}

/** One role: its name and the permissions it grants, each a code. */
function RoleRow({ role, onEdit }: { role: RoleSummary; onEdit: () => void }) {
  const permissions = role.permissions ?? [];
  return (
    <TableRow>
      <TableCell className="font-medium">{role.name}</TableCell>
      <TableCell className="whitespace-normal">
        <span className="sr-only">{permissions.length} permissões: </span>
        <span className="flex flex-wrap gap-1">
          {permissions.map((permission) => (
            <Badge key={permission} variant="secondary" className="font-mono normal-case">
              {permission}
            </Badge>
          ))}
        </span>
      </TableCell>
      <TableCell>
        <div className="flex justify-end">
          <Button variant="outline" size="sm" aria-label={`Editar ${role.name}`} onClick={onEdit}>
            <Pencil aria-hidden="true" />
            Editar
          </Button>
        </div>
      </TableCell>
    </TableRow>
  );
}
