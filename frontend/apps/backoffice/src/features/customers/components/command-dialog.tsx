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
export type CommandRunner<Body> = {
  mutate: (
    variables: { body: Body; idempotencyKey: string },
    options: { onSuccess: () => void; onError: (error: unknown) => void },
  ) => void;
  isPending: boolean;
  error: unknown;
};

type CommandDialogProps<Values extends FieldValues, Body> = {
  /** The button that opens the dialog. */
  trigger: ReactNode;
  title: string;
  description: string;
  submitLabel: string;
  schema: z.ZodType<Body, Values>;
  defaultValues: DefaultValues<Values>;
  /** The form's fields, given the form. */
  fields: (form: UseFormReturn<Values>) => ReactNode;
  runner: CommandRunner<Body>;
};

/**
 * One BackOffice command in a dialog: a Zod-checked form (pt-PT messages), its request sent under the key of its
 * intent, the server's field errors on their fields, and the dialog closed only once the server has confirmed. The
 * form is mounted only while open, so each opening is a new intent.
 */
export function CommandDialog<Values extends FieldValues, Body>({ trigger, title, description, ...form }: CommandDialogProps<Values, Body>) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <CommandForm {...form} onDone={() => setOpen(false)} onCancel={() => setOpen(false)} />
      </DialogContent>
    </Dialog>
  );
}

type CommandFormProps<Values extends FieldValues, Body> = Omit<CommandDialogProps<Values, Body>, 'trigger' | 'title' | 'description'> & {
  onDone: () => void;
  onCancel: () => void;
};

function CommandForm<Values extends FieldValues, Body>({
  submitLabel,
  schema,
  defaultValues,
  fields,
  runner,
  onDone,
  onCancel,
}: CommandFormProps<Values, Body>) {
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues });
  const [intent, setIntent] = useState<Intent<Body>>();

  const send = (body: Body) => {
    const current = nextIntent(intent, body, () => crypto.randomUUID());
    setIntent(current);
    runner.mutate(
      { body, idempotencyKey: current.key },
      { onSuccess: onDone, onError: (error) => showOnFields(form, error) },
    );
  };
  // The resolver has checked the values; parsing them again gives the request body its type (trim, decimal dot).
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
        {!serverFieldErrors(runner.error) && <CommandFeedback error={runner.error} pending={runner.isPending} onRetry={() => intent && send(intent.body)} />}
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
