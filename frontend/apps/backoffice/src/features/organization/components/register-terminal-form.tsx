import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError } from '@restaurantpro/api-client';
import { ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { useForm, type UseFormReturn } from 'react-hook-form';
import { isStepUpRequired } from '../../../lib/step-up';
import { useCreateTerminal } from '../hooks/use-terminal-commands';
import { useTerminalStepUp } from '../hooks/use-step-up-command';
import { registerTerminalSchema, type RegisterTerminalValues } from '../schemas';
import type { TerminalCreateRequest, TerminalSummary } from '../types';
import { CommandError } from './command-error';

type RegisterTerminalFormProps = {
  storeId: string;
  /** Where a step-up comes back to once the command has run. */
  returnTo: string;
  submitLabel: string;
  onCreated: (terminal: TerminalSummary) => void;
  defaultValues?: RegisterTerminalValues;
  /** Called just before each request leaves (the resume page forgets its stored command there). */
  onSend?: () => void;
  /** Further actions beside the submit button (Cancelar). */
  children?: ReactNode;
};

const FIELDS = new Set<string>(['code', 'name']);

/**
 * Story 13: a POS terminal of the store, with its code (unique in the store) and name. Server field errors land on
 * their fields (story 19), a taken code on the code field; "Sem ligação" offers the same request again (story 20).
 */
export function RegisterTerminalForm({
  storeId,
  returnTo,
  submitLabel,
  onCreated,
  defaultValues,
  onSend,
  children,
}: RegisterTerminalFormProps) {
  const form = useForm<RegisterTerminalValues>({
    resolver: zodResolver(registerTerminalSchema),
    defaultValues: defaultValues ?? { code: '', name: '' },
  });
  const create = useCreateTerminal();
  const stepUp = useTerminalStepUp(returnTo);

  const send = (body: TerminalCreateRequest) => {
    onSend?.();
    create.mutate(body, {
      onSuccess: onCreated,
      onError: (error) => {
        if (isStepUpRequired(error)) stepUp.prompt(error, { type: 'create', body });
        else showOnFields(form, error);
      },
    });
  };

  const onSubmit = form.handleSubmit(({ code, name }) => send({ storeId, code, name, kind: 'POS' }));
  const generalError = create.error && !isStepUpRequired(create.error) && !onFields(create.error);
  const rootMessage = form.formState.errors.root?.message;

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="code"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Código</FormLabel>
              <FormControl>
                <Input {...field} autoComplete="off" maxLength={20} className="max-w-48 uppercase" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Nome</FormLabel>
              <FormControl>
                <Input {...field} autoComplete="off" maxLength={120} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        {rootMessage && (
          <p role="alert" className="text-sm text-destructive">
            {rootMessage}
          </p>
        )}
        {generalError && (
          <CommandError error={create.error} pending={create.isPending} onRetry={() => create.variables && send(create.variables)} />
        )}
        <div className="flex flex-wrap justify-end gap-2">
          {children}
          <ConfirmButton type="submit" pending={create.isPending}>
            {submitLabel}
          </ConfirmButton>
        </div>
      </form>
      {stepUp.dialog}
    </Form>
  );
}

/** A 400 with field violations, or a taken code: the answer is shown on the fields, not as a general error. */
function onFields(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  return (error.status === 400 && !!error.errors?.length) || isCodeTaken(error);
}

/** TERMINAL_CODE_TAKEN; an older server sent it as the only uncoded 409 of this request, so that counts too. */
function isCodeTaken(error: ApiError): boolean {
  return error.status === 409 && (error.code === 'TERMINAL_CODE_TAKEN' || error.code === 'HTTP_409');
}

function showOnFields(form: UseFormReturn<RegisterTerminalValues>, error: unknown) {
  if (!(error instanceof ApiError)) return;
  if (isCodeTaken(error)) {
    form.setError('code', { message: 'Já existe um terminal com este código nesta loja.' });
    return;
  }
  if (error.status !== 400 || !error.errors?.length) return;
  const others: string[] = [];
  for (const { field, message } of error.errors) {
    if (FIELDS.has(field)) form.setError(field as keyof RegisterTerminalValues, { message });
    else others.push(message);
  }
  if (others.length) form.setError('root', { message: others.join(' ') });
}
