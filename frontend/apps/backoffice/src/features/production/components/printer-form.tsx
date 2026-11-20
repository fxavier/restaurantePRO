import { zodResolver } from '@hookform/resolvers/zod';
import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { useForm, type UseFormReturn } from 'react-hook-form';
import { fieldErrors } from '../form-errors';
import { CODE_PAGES } from '../parse';
import { printerSchema, type PrinterFormValues } from '../schemas';
import { NativeSelect } from './native-select';
import { ProblemAlert } from './problem-alert';

const FIELDS = ['code', 'name', 'host', 'port', 'codePage'] as const;

type PrinterFormProps = {
  defaultValues: PrinterFormValues;
  submitLabel: string;
  /** Sends the values; rejects with the API's answer. */
  send: (values: PrinterFormValues) => Promise<unknown>;
  pending: boolean;
  /** The last answer that was not about the fields, shown above the actions. */
  error: unknown;
  /** Fields only one of the dialogs has (the preset, the active switch); they read the form from its context. */
  extraFields?: ReactNode;
  /** Further actions beside the submit button (Cancelar). */
  children?: ReactNode;
};

/** A printer's identity and address (CONTEXT: Printer, ESC/POS on TCP 9100); server field errors land on their fields. */
export function PrinterForm({ defaultValues, submitLabel, send, pending, error, extraFields, children }: PrinterFormProps) {
  const form = useForm<PrinterFormValues>({ resolver: zodResolver(printerSchema), defaultValues });

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      await send(values);
    } catch (refused) {
      showOnFields(form, refused);
    }
  });
  const general = error && !fieldErrors(error, FIELDS, { PRINTER_CODE_TAKEN: 'code' });
  const rootMessage = form.formState.errors.root?.message;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField form={form} name="code" label="Código" maxLength={20} className="uppercase" />
          <TextField form={form} name="name" label="Nome" maxLength={120} />
          <TextField form={form} name="host" label="Endereço IP" maxLength={255} className="font-mono" />
          <TextField form={form} name="port" label="Porta" inputMode="numeric" maxLength={5} className="font-mono" />
          <FormField
            control={form.control}
            name="codePage"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Página de código</FormLabel>
                <FormControl>
                  <NativeSelect {...field}>
                    {CODE_PAGES.map((codePage) => (
                      <option key={codePage} value={codePage}>
                        {codePage}
                      </option>
                    ))}
                  </NativeSelect>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          {extraFields}
        </div>
        {rootMessage && (
          <p role="alert" className="text-sm text-error">
            {rootMessage}
          </p>
        )}
        {general ? <ProblemAlert error={error} pending={pending} onRetry={() => void onSubmit()} /> : null}
        <div className="flex flex-wrap justify-end gap-2">
          {children}
          <ConfirmButton type="submit" pending={pending}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}

type TextFieldProps = {
  form: UseFormReturn<PrinterFormValues>;
  name: 'code' | 'name' | 'host' | 'port';
  label: string;
  maxLength: number;
  className?: string;
  inputMode?: 'numeric';
};

function TextField({ form, name, label, ...input }: TextFieldProps) {
  return (
    <FormField
      control={form.control}
      name={name}
      render={({ field }) => (
        <FormItem>
          <FormLabel>{label}</FormLabel>
          <FormControl>
            <Input {...field} autoComplete="off" {...input} />
          </FormControl>
          <FormMessage />
        </FormItem>
      )}
    />
  );
}

function showOnFields(form: UseFormReturn<PrinterFormValues>, refused: unknown) {
  const errors = fieldErrors(refused, FIELDS, { PRINTER_CODE_TAKEN: 'code' });
  if (!errors) return;
  for (const name of FIELDS) {
    const message = errors.fields[name];
    if (message) form.setError(name, { message });
  }
  if (errors.others.length) form.setError('root', { message: errors.others.join(' ') });
}
