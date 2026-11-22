import { zodResolver } from '@hookform/resolvers/zod';
import { Form, FormField } from '@restaurantpro/ui';
import { useForm } from 'react-hook-form';
import { usePermissionMatrix } from '../hooks/use-choices';
import { useFormCommand } from '../hooks/use-form-command';
import { useLoadedVersion } from '../hooks/use-loaded-version';
import { useSaveRole } from '../hooks/use-people-commands';
import { roleSchema, type RoleValues } from '../schemas';
import type { RoleSummary } from '../types';
import { FormFooter } from './form-footer';
import { TextField } from './form-fields';
import { PermissionMatrix } from './permission-matrix';

type RoleFormProps = {
  /** The role being edited, as the list holds it now (refetched after a conflict); none for a new role. */
  role?: RoleSummary;
  onClose: () => void;
};

function valuesOf(role: RoleSummary | undefined): RoleValues {
  return { name: role?.name ?? '', permissions: role?.permissions ?? [] };
}

/**
 * A role's name and the permissions it grants (§5, RBAC), switched on or off in the matrix of the server's permission
 * catalogue. A conflicting edit (409 VERSION_CONFLICT) offers the current version, then saves against it; a save that
 * needs a fresh second factor asks for it.
 */
export function RoleForm({ role, onClose }: RoleFormProps) {
  const save = useSaveRole();
  const catalogue = usePermissionMatrix();
  const form = useForm<RoleValues>({ resolver: zodResolver(roleSchema), defaultValues: valuesOf(role) });
  const loaded = useLoadedVersion(form, save, role, () => valuesOf(role));
  const command = useFormCommand(form, save, { conflicts: { ROLE_NAME_TAKEN: 'name' }, onDone: onClose });
  const onSubmit = form.handleSubmit((body) => command.send({ roleId: role?.id, body: { ...body, version: loaded.version } }));

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="name" label="Nome" maxLength={60} />
        <FormField
          control={form.control}
          name="permissions"
          render={({ field }) => <PermissionMatrix catalogue={catalogue} value={field.value} onChange={field.onChange} />}
        />
        <FormFooter
          onLoadCurrent={role && loaded.loadCurrent}
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={save.isPending}
          onRetry={() => save.variables && command.send(save.variables)}
          onCancel={onClose}
          submitLabel={role ? 'Guardar' : 'Criar função'}
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}
