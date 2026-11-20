import { zodResolver } from '@hookform/resolvers/zod';
import { Button, ConfirmButton, Form } from '@restaurantpro/ui';
import { Pencil } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { companyProfileSchema, type CompanyProfileValues } from '../company-schemas';
import { useCommandForm } from '../hooks/use-command-form';
import { useReadCompanyProfile, useUpdateCompanyProfile } from '../hooks/use-company-profile';
import { useEditBase } from '../hooks/use-edit-base';
import type { CompanyProfileView } from '../store-types';
import { ConflictNotice } from './conflict-notice';
import { FormDialog } from './form-dialog';
import { FormError, RootError } from './form-error';
import { TextField } from './form-fields';

const FIELDS = ['name', 'taxId', 'country', 'currency'] as const;
/** The profile's one uncoded rule (422): the currency is fixed once the company has a store. */
const MESSAGES = { HTTP_422: 'A moeda da empresa não pode mudar depois de criada a primeira loja.' };

/** "Editar perfil" (§5): the company's legal name, tax number, country and currency, at the version read. */
export function EditCompanyProfileDialog({ profile }: { profile: CompanyProfileView }) {
  return (
    <FormDialog
      title="Editar perfil da empresa"
      description="Os dados legais que identificam a empresa."
      trigger={
        <Button variant="outline">
          <Pencil aria-hidden="true" />
          Editar perfil
        </Button>
      }
    >
      {(close) => <EditCompanyProfile profile={profile} onDone={close} />}
    </FormDialog>
  );
}

function EditCompanyProfile({ profile, onDone }: { profile: CompanyProfileView; onDone: () => void }) {
  const update = useUpdateCompanyProfile();
  const read = useReadCompanyProfile();
  const { base, reloaded, save } = useEditBase(profile);
  return (
    <>
      <ConflictNotice
        shown={reloaded}
        text="O perfil foi alterado por outra pessoa entretanto. Estes são os valores atuais: reveja-os e guarde de novo."
      />
      <CompanyProfileForm
        key={base.version}
        defaultValues={{
          name: base.name ?? '',
          taxId: base.taxId ?? '',
          country: base.country ?? '',
          currency: base.currency ?? '',
        }}
        onSubmit={(values) =>
          save(async ({ version }) => {
            await update.mutateAsync({ ...values, version: version ?? 0 });
            onDone();
          }, read)
        }
        onCancel={onDone}
      />
    </>
  );
}

type FormProps = {
  defaultValues: CompanyProfileValues;
  onSubmit: (values: CompanyProfileValues) => Promise<unknown>;
  onCancel: () => void;
};

function CompanyProfileForm({ defaultValues, onSubmit, onCancel }: FormProps) {
  const form = useForm<CompanyProfileValues>({ resolver: zodResolver(companyProfileSchema), defaultValues });
  const { error, submit } = useCommandForm(form, FIELDS);
  const handle = submit(onSubmit);
  const { control } = form;
  return (
    <Form {...form}>
      <form onSubmit={(event) => void handle(event)} noValidate className="flex flex-col gap-4">
        <TextField control={control} name="name" label="Nome legal" maxLength={200} />
        <TextField control={control} name="taxId" label="NUIT / NIF" maxLength={50} />
        <div className="grid grid-cols-2 gap-4">
          <TextField control={control} name="country" label="País" maxLength={2} className="uppercase" />
          <TextField control={control} name="currency" label="Moeda" maxLength={3} className="uppercase" />
        </div>
        <RootError message={form.formState.errors.root?.message} />
        <FormError error={error} messages={MESSAGES} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={onCancel}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={form.formState.isSubmitting}>
            Guardar
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
