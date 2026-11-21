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
import type { RoleSummary } from '../types';
import { FormDialog } from './form-dialog';
import { ListCard } from './list-card';
import { RoleForm } from './role-form';

/** "Funções" (§5, RBAC): each role and the permissions it grants wherever it is assigned. */
export function RolesTab() {
  const [page, setPage] = useState(0);
  const roles = useRoles(page);
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  // The role as the list holds it now, so a conflict's refetch reaches the open form.
  const editing = roles.data?.rows.find((role) => role.id === editingId);

  return (
    <>
      <ListCard
        title="Funções"
        description="Cada função concede permissões em todas as lojas onde é atribuída."
        actions={
          <Button onClick={() => setCreating(true)}>
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
                <RoleRow key={role.id} role={role} onEdit={() => setEditingId(role.id ?? '')} />
              ))}
            </TableBody>
          </Table>
        )}
      </ListCard>
      <FormDialog
        title="Nova função"
        description="Dê-lhe um nome e as permissões que concede. Atribua-a depois a empregados ou a contas."
        open={creating}
        onClose={() => setCreating(false)}
      >
        <RoleForm onClose={() => setCreating(false)} />
      </FormDialog>
      <FormDialog
        title={`Editar a função ${editing?.name}`}
        description="As alterações valem de imediato para todos os que têm esta função."
        open={editing !== undefined}
        onClose={() => setEditingId(null)}
      >
        <RoleForm role={editing} onClose={() => setEditingId(null)} />
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
