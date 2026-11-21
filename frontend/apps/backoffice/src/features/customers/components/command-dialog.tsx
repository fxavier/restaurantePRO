import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError } from '@restaurantpro/api-client';
import {
  Button,
  ConfirmButton,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  Input,
} from '@restaurantpro/ui';
import { useState, type ComponentProps, type ReactNode } from 'react';
import { useForm, type DefaultValues, type FieldValues, type Path, type UseFormReturn } from 'react-hook-form';
import type { z } from 'zod';
import { nextIntent, type Intent } from '../intent';
import { CommandFeedback } from './command-feedback';

/** A command mutation as the dialogs use it: the body and the key of its intent; never retried by itself (FC §8). */
export type CommandRunner<Body, Result> = {
  mutate: (
    variables: { body: Body; idempotencyKey: string },
    options: { onSuccess: (result: Result) => void; onError: (error: unknown) => void },
  ) => void;
  isPending: boolean;
  error: unknown;
  /** Forgets the previous attempt's outcome, so a new opening starts clean. */
  reset: () => void;
};

type CommandDialogProps<Values extends FieldValues, Body, Result> = {
  /** The button that opens the dialog. */
  trigger: ReactNode;
  title: string;
  description: string;
  submitLabel: string;
  schema: z.ZodType<Body, Values>;
  defaultValues: DefaultValues<Values>;
  /** The form's fields, given the form. */
  fields: (form: UseFormReturn<Values>) => ReactNode;
  runner: CommandRunner<Body, Result>;
  /** Called with the server's answer, once the dialog has closed. */
  onDone?: (result: Result) => void;
};

/**
 * One BackOffice command in a dialog: a Zod-checked form (pt-PT messages), its request sent under the key of its
 * intent, the server's field errors on their fields, and the dialog closed only once the server has confirmed. The
 * form is mounted only while open, so each opening is a new intent.
 */
export function CommandDialog<Values extends FieldValues, Body, Result>({
  trigger,
  title,
  description,
  onDone,
  ...form
}: CommandDialogProps<Values, Body, Result>) {
  const [open, setOpen] = useState(false);
  const openChange = (next: boolean) => {
    if (next) form.runner.reset();
    setOpen(next);
  };
  const done = (result: Result) => {
    setOpen(false);
    onDone?.(result);
  };
  return (
    <Dialog open={open} onOpenChange={openChange}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <CommandForm {...form} onDone={done} onCancel={() => setOpen(false)} />
      </DialogContent>
    </Dialog>
  );
}

type ReasonValues = { reason: string };

/** A command whose only field is its reason (block, unblock, suspend, resume). */
export function ReasonDialog<Body, Result>({
  reasonLabel = 'Motivo',
  ...props
}: Omit<CommandDialogProps<ReasonValues, Body, Result>, 'defaultValues' | 'fields'> & { reasonLabel?: string }) {
  return (
    <CommandDialog
      {...props}
      defaultValues={{ reason: '' }}
      fields={(form) => <TextField form={form} name="reason" label={reasonLabel} maxLength={500} />}
    />
  );
}

type CommandFormProps<Values extends FieldValues, Body, Result> = Omit<
  CommandDialogProps<Values, Body, Result>,
  'trigger' | 'title' | 'description' | 'onDone'
> & {
  onDone: (result: Result) => void;
  onCancel: () => void;
};

function CommandForm<Values extends FieldValues, Body, Result>({
  submitLabel,
  schema,
  defaultValues,
  fields,
  runner,
  onDone,
  onCancel,
}: CommandFormProps<Values, Body, Result>) {
  // Raw: the form keeps what was typed; the body is parsed once, on submit (trimmed, blanks dropped, decimal dot).
  const form = useForm<Values>({ resolver: zodResolver(schema, undefined, { raw: true }), defaultValues });
  const [intent, setIntent] = useState<Intent<Body>>();

  const send = (body: Body) => {
    const current = nextIntent(intent, body, () => crypto.randomUUID());
    setIntent(current);
    runner.mutate(
      { body, idempotencyKey: current.key },
      { onSuccess: onDone, onError: (error) => showOnFields(form, error) },
    );
  };
  const submit = form.handleSubmit((values) => send(schema.parse(values)));
  const rootMessage = form.formState.errors.root?.message;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void submit(event)} noValidate className="flex flex-col gap-4">
        {fields(form)}
        {rootMessage && (
          <p role="alert" className="text-sm text-error">
            {rootMessage}
          </p>
        )}
        {!serverFieldErrors(runner.error) && (
          <CommandFeedback error={runner.error} pending={runner.isPending} onRetry={() => intent && send(intent.body)} />
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={onCancel}>
            Cancelar
          </Button>
          <ConfirmButton type="submit" pending={runner.isPending}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}

/** A 400 with field violations (FC §9): each on its field when the form has it, the rest as one message. */
function serverFieldErrors(error: unknown) {
  return error instanceof ApiError && error.status === 400 && error.errors?.length ? error.errors : undefined;
}

function showOnFields<Values extends FieldValues>(form: UseFormReturn<Values>, error: unknown) {
  const errors = serverFieldErrors(error);
  if (!errors) return;
  const names = new Set(Object.keys(form.getValues()));
  const others: string[] = [];
  for (const { field, message } of errors) {
    if (names.has(field)) form.setError(field as Path<Values>, { message });
    else others.push(message);
  }
  if (others.length) form.setError('root', { message: others.join(' ') });
}

type TextFieldProps<Values extends FieldValues> = {
  form: UseFormReturn<Values>;
  name: Path<Values>;
  label: string;
} & Omit<ComponentProps<typeof Input>, 'form' | 'name'>;

/** One labelled text field of a command form, with its message. */
export function TextField<Values extends FieldValues>({ form, name, label, ...input }: TextFieldProps<Values>) {
  return (
    <FormField
      control={form.control}
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

/** A yes/no field of a command form, as a native checkbox with its label. */
export function CheckboxField<Values extends FieldValues>({
  form,
  name,
  label,
}: {
  form: UseFormReturn<Values>;
  name: Path<Values>;
  label: string;
}) {
  return (
    <FormField
      control={form.control}
      name={name}
      render={({ field }) => (
        <FormItem className="flex flex-row items-center gap-2">
          <FormControl>
            <input
              type="checkbox"
              checked={Boolean(field.value)}
              onChange={(event) => field.onChange(event.target.checked)}
              onBlur={field.onBlur}
              name={field.name}
              ref={field.ref}
              className="size-5 accent-primary"
            />
          </FormControl>
          <FormLabel>{label}</FormLabel>
        </FormItem>
      )}
    />
  );
}
