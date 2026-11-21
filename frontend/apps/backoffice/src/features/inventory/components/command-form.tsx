import {
  Button,
  ConfirmButton,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  Input,
} from '@restaurantpro/ui';
import type { ComponentProps, FormEvent, ReactNode } from 'react';
import type { Control, FieldValues, Path, UseFormReturn } from 'react-hook-form';
import { CommandError } from './command-error';

type CommandFormProps<Values extends FieldValues, Output extends FieldValues> = {
  form: UseFormReturn<Values, unknown, Output>;
  onSubmit: (values: Output) => void;
  command: { error: unknown; retry: () => void };
  pending: boolean;
  submitLabel: string;
  onCancel: () => void;
  children: ReactNode;
};

/** A dialog's form: its fields, the form-level and command errors, Cancelar and the one-tap submit (FC §8, §9). */
export function CommandForm<Values extends FieldValues, Output extends FieldValues>({
  form,
  onSubmit,
  command,
  pending,
  submitLabel,
  onCancel,
  children,
}: CommandFormProps<Values, Output>) {
  const rootMessage = form.formState.errors.root?.message;
  const submit = (event: FormEvent) => void form.handleSubmit(onSubmit)(event);
  return (
    <Form {...form}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4">
        {children}
        {rootMessage && (
          <p role="alert" className="text-sm text-error">
            {rootMessage}
          </p>
        )}
        <CommandError error={command.error} pending={pending} onRetry={command.retry} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={onCancel}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={pending}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}

type FieldProps<Values extends FieldValues> = { control: Control<Values, unknown, FieldValues>; name: Path<Values>; label: string };

/** A labelled text input with its message. */
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
            <Input autoComplete="off" {...input} {...field} value={(field.value as string | undefined) ?? ''} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

/** A labelled native select (keyboard and screen-reader friendly) with its message. */
export function SelectField<Values extends FieldValues>({
  control,
  name,
  label,
  options,
  placeholder = 'Escolha…',
}: FieldProps<Values> & { options: { value: string; label: string }[]; placeholder?: string }) {
  return (
    <FormField
      control={control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <select className={SELECT} {...field} value={(field.value as string | undefined) ?? ''}>
              <option value="">{placeholder}</option>
              {options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
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

/** The native select styled like the design system's inputs. */
export const SELECT = 'h-9 w-full rounded-md border border-input bg-muted px-3 text-sm';
