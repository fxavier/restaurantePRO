import { zodResolver } from '@hookform/resolvers/zod';
import { Button, Form } from '@restaurantpro/ui';
import { Pencil, Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { nonFieldError } from '../form-errors';
import { useSaveFamily } from '../hooks/use-catalog-commands';
import { familySchema, type FamilyValues } from '../schemas';
import type { FamilySummary } from '../types';
import { FormDialog } from './form-dialog';
import { FormFooter, SelectField, showOnFields, TextField } from './form-parts';

type Props = { family?: FamilySummary; families: readonly FamilySummary[] };

/** "Nova família", or "Editar família" when given one. */
export function FamilyFormDialog({ family, families }: Props) {
  const [open, setOpen] = useState(false);
  const trigger = family ? (
    <Button variant="outline" size="sm" aria-label={`Editar ${family.name}`}>
      <Pencil aria-hidden="true" />
      Editar
    </Button>
  ) : (
    <Button>
      <Plus aria-hidden="true" />
      Nova família
    </Button>
  );
  return (
    <FormDialog
      title={family ? 'Editar família' : 'Nova família'}
      description="As famílias agrupam os artigos nos terminais POS; uma subfamília indica a família superior."
      open={open}
      onOpenChange={setOpen}
      trigger={trigger}
    >
      <FamilyForm family={family} families={families} onDone={() => setOpen(false)} />
    </FormDialog>
  );
}

const FIELDS = ['code', 'name', 'parentId'] as const;

function FamilyForm({ family, families, onDone }: Props & { onDone: () => void }) {
  const form = useForm<FamilyValues>({
    resolver: zodResolver(familySchema),
    defaultValues: { code: family?.code ?? '', name: family?.name ?? '', parentId: family?.parentId ?? '' },
  });
  const save = useSaveFamily();
  const parents = families.filter((candidate) => candidate.id !== family?.id).map((candidate) => ({ value: candidate.id ?? '', label: candidate.name ?? '' }));

  const send = (values: FamilyValues) =>
    save.mutate(
      { id: family?.id, version: family?.version, body: { code: values.code, name: values.name, parentId: values.parentId || undefined } },
      { onSuccess: onDone, onError: (error) => showOnFields(form, error, FIELDS) },
    );
  const onSubmit = form.handleSubmit(send);
  const generalError = nonFieldError(save.error, FIELDS);

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <TextField control={form.control} name="code" label="Código" maxLength={30} className="max-w-48 uppercase" />
        <TextField control={form.control} name="name" label="Nome" maxLength={120} />
        <SelectField control={form.control} name="parentId" label="Família superior" options={parents} placeholder="Nenhuma" />
        <FormFooter
          submitLabel={family ? 'Guardar' : 'Criar'}
          pending={save.isPending}
          error={generalError}
          rootMessage={form.formState.errors.root?.message}
          onCancel={onDone}
          onRetry={() => void onSubmit()}
        />
      </form>
    </Form>
  );
}
