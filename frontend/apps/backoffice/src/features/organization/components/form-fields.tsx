import { FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ComponentProps } from 'react';
import type { Control, FieldValues, Path } from 'react-hook-form';

type FieldProps<Values extends FieldValues> = {
  control: Control<Values>;
  name: Path<Values>;
  label: string;
};

type TextFieldProps<Values extends FieldValues> = FieldProps<Values> & {
  /** Classes of the field's wrapper (its grid span). */
  className?: string;
} & Omit<ComponentProps<'input'>, 'name' | 'className'>;

/** A labelled input of a configuration form, with its error under it. */
export function TextField<Values extends FieldValues>({ control, name, label, className, ...input }: TextFieldProps<Values>) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem className={className}>
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

/** "Ativo/a": a native checkbox, labelled on its right. */
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
              checked={field.value as boolean}
              onChange={(event) => field.onChange(event.target.checked)}
              onBlur={field.onBlur}
              ref={field.ref}
              name={field.name}
              className="size-5 accent-primary"
            />
          </FormControl>
          <FormLabel>{label}</FormLabel>
        </FormItem>
      )}
    />
  );
}
