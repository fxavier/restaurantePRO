import { FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ComponentProps } from 'react';
import type { Control, FieldPath, FieldValues } from 'react-hook-form';
import { useStoreAndRoleChoices, type ChoiceList } from '../hooks/use-choices';
import { ChoicesError } from './choices-error';

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

/**
 * A labelled native select (keyboard and screen reader friendly, no popover) over the server's choices, with its
 * message; while they load it says so, and a failed read is said under it.
 */
export function SelectField<Values extends FieldValues>({
  control,
  name,
  label,
  list,
  placeholder,
}: FieldProps<Values> & { list: ChoiceList; placeholder: string }) {
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
              className="h-9 w-full rounded-xl border border-input bg-card px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-invalid:border-destructive"
            >
              <option value="">{list.pending ? 'A carregar…' : placeholder}</option>
              {list.choices.map((choice) => (
                <option key={choice.value} value={choice.value}>
                  {choice.label}
                </option>
              ))}
            </select>
          </FormControl>
          <FormMessage />
          <ChoicesError list={list} />
        </FormItem>
      )}
    />
  );
}

/** A labelled checkbox over a boolean field. */
export function CheckboxField<Values extends FieldValues>({ control, name, label }: FieldProps<Values>) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem className="flex flex-row items-center gap-2">
          <FormControl>
            <input
              type="checkbox"
              className="size-4 accent-primary"
              checked={field.value}
              onChange={(event) => field.onChange(event.target.checked)}
              onBlur={field.onBlur}
              ref={field.ref}
            />
          </FormControl>
          <FormLabel className="font-normal">{label}</FormLabel>
        </FormItem>
      )}
    />
  );
}

type StoreAndRole = { storeId: string; roleId: string };

/** "Loja" and "Função", the pair an employee is hired or assigned with. */
export function StoreAndRoleFields<Values extends StoreAndRole>({ control }: { control: Control<Values> }) {
  const { stores, roles } = useStoreAndRoleChoices();
  return (
    <>
      <SelectField control={control} name={'storeId' as FieldPath<Values>} label="Loja" placeholder="Escolher loja" list={stores} />
      <SelectField control={control} name={'roleId' as FieldPath<Values>} label="Função" placeholder="Escolher função" list={roles} />
    </>
  );
}
