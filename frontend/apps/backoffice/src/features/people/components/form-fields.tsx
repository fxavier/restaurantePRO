import { FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ComponentProps } from 'react';
import type { Control, FieldPath, FieldValues } from 'react-hook-form';
import { useStoreAndRoleChoices, type Choice } from '../hooks/use-choices';

type FieldProps<Values extends FieldValues> = { control: Control<Values>; name: FieldPath<Values>; label: string };

/** A labelled text input with its message, for React Hook Form. */
export function TextField<Values extends FieldValues>({
  control,
  name,
  label,
  ...input
}: FieldProps<Values> & Omit<ComponentProps<typeof Input>, 'name'>) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input autoComplete="off" {...input} {...field} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

/** A labelled native select (keyboard and screen reader friendly, no popover) with its message. */
export function SelectField<Values extends FieldValues>({
  control,
  name,
  label,
  choices,
  placeholder,
}: FieldProps<Values> & { choices: Choice[]; placeholder: string }) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <select
              {...field}
              className="h-9 w-full rounded-md border border-input bg-muted px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-invalid:border-destructive"
            >
              <option value="">{placeholder}</option>
              {choices.map((choice) => (
                <option key={choice.value} value={choice.value}>
                  {choice.label}
                </option>
              ))}
            </select>
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

type StoreAndRole = { storeId: string; roleId: string };

/** "Loja" and "Função", the pair an employee is hired or assigned with. */
export function StoreAndRoleFields<Values extends StoreAndRole>({ control }: { control: Control<Values> }) {
  const choices = useStoreAndRoleChoices();
  return (
    <>
      <SelectField
        control={control}
        name={'storeId' as FieldPath<Values>}
        label="Loja"
        placeholder={choices.storesPending ? 'A carregar…' : 'Escolher loja'}
        choices={choices.storeChoices}
      />
      <SelectField
        control={control}
        name={'roleId' as FieldPath<Values>}
        label="Função"
        placeholder={choices.rolesPending ? 'A carregar…' : 'Escolher função'}
        choices={choices.roleChoices}
      />
    </>
  );
}
