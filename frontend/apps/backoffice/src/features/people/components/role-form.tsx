import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Form, FormField, Input, Label } from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useId, useState, type KeyboardEvent } from 'react';
import { useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useLoadedVersion } from '../hooks/use-loaded-version';
import { useSaveRole } from '../hooks/use-people-commands';
import { permissionName, roleSchema, type RoleValues } from '../schemas';
import type { RoleSummary } from '../types';
import { CheckboxGroup } from './checkbox-group';
import { FormFooter } from './form-footer';
import { TextField } from './form-fields';

type RoleFormProps = {
  /** The role being edited, as the list holds it now (refetched after a conflict); none for a new role. */
  role?: RoleSummary;
  onClose: () => void;
};

function valuesOf(role: RoleSummary | undefined): RoleValues {
  return { name: role?.name ?? '', permissions: role?.permissions ?? [] };
}

/**
 * A role's name and the permissions it grants (§5, RBAC). Without a permission catalogue endpoint the permissions are
 * the names the role holds, each a checkbox, and names added by hand; the server rejects a name that does not exist.
 * A conflicting edit (409 VERSION_CONFLICT) offers the current version, then saves against it.
 */
export function RoleForm({ role, onClose }: RoleFormProps) {
  const save = useSaveRole();
  const form = useForm<RoleValues>({ resolver: zodResolver(roleSchema), defaultValues: valuesOf(role) });
  const [shown, setShown] = useState<string[]>(valuesOf(role).permissions);
  const loaded = useLoadedVersion(form, save, role, () => {
    setShown(valuesOf(role).permissions);
    return valuesOf(role);
  });
  const command = useFormCommand(form, save, { conflicts: { ROLE_NAME_TAKEN: 'name' }, onDone: onClose });
  const onSubmit = form.handleSubmit((body) => command.send({ roleId: role?.id, body: { ...body, version: loaded.version } }));

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="name" label="Nome" maxLength={60} />
        <FormField
          control={form.control}
          name="permissions"
          render={({ field }) => (
            <CheckboxGroup
              legend="Permissões"
              mono
              choices={shown.map((name) => ({ value: name, label: name }))}
              value={field.value}
              onChange={field.onChange}
              emptyMessage="Esta função ainda não concede permissões."
            >
              <AddPermission
                onAdd={(name) => {
                  if (!shown.includes(name)) setShown([...shown, name]);
                  if (!field.value.some((held) => held === name)) field.onChange([...field.value, name]);
                }}
              />
            </CheckboxGroup>
          )}
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

/** A permission typed by name, added (checked) to the role; Enter adds it rather than submitting the form. */
function AddPermission({ onAdd }: { onAdd: (name: string) => void }) {
  const id = useId();
  const [text, setText] = useState('');
  const [invalid, setInvalid] = useState(false);

  const add = () => {
    const name = permissionName(text);
    setInvalid(!name);
    if (!name) return;
    onAdd(name);
    setText('');
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    add();
  };

  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id}>Adicionar permissão</Label>
      <div className="flex gap-2">
        <Input
          id={id}
          value={text}
          autoComplete="off"
          aria-invalid={invalid}
          aria-describedby={invalid ? `${id}-error` : undefined}
          className="max-w-64 font-mono uppercase"
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKeyDown}
        />
        <Button type="button" variant="outline" onClick={add}>
          <Plus aria-hidden="true" />
          Adicionar
        </Button>
      </div>
      {invalid && (
        <p id={`${id}-error`} className="text-sm text-error">
          Escreva o nome da permissão, por exemplo ORDER_VIEW.
        </p>
      )}
    </div>
  );
}
