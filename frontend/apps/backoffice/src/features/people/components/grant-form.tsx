import { zodResolver } from '@hookform/resolvers/zod';
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@restaurantpro/ui';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useGrantSupport } from '../hooks/use-people-commands';
import { nextIntent, type Intent } from '../intent';
import { grantSchema, toGrantRequest, type GrantValues } from '../schemas';
import type { ChoiceList } from '../hooks/use-choices';
import type { SupportGrantRequest } from '../types';
import { FormFooter } from './form-footer';
import { SelectField, TextField } from './form-fields';

type GrantFormProps = { supportAccounts: ChoiceList; onClose: () => void };

/**
 * "Conceder acesso de suporte" (D83): which support account, the support person's identifier in the operator's
 * realm, for how long (at most 24 h) and why. One Idempotency-Key per intent: "Tentar novamente" and an unchanged
 * resubmit reuse it, a corrected request gets a new one (FC §8).
 */
export function GrantForm({ supportAccounts, onClose }: GrantFormProps) {
  const grant = useGrantSupport();
  const form = useForm<GrantValues>({
    resolver: zodResolver(grantSchema),
    defaultValues: { accountId: '', subject: '', durationMinutes: '60', reason: '' },
  });
  const [intent, setIntent] = useState<Intent<SupportGrantRequest>>();
  const command = useFormCommand(form, grant, { conflicts: { SUPPORT_GRANT_ACTIVE: 'accountId' }, onDone: onClose });
  const onSubmit = form.handleSubmit((values) => {
    const current = nextIntent(intent, toGrantRequest(values), () => crypto.randomUUID());
    setIntent(current);
    command.send({ body: current.body, idempotencyKey: current.key });
  });

  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <SelectField
          control={form.control}
          name="accountId"
          label="Conta de suporte"
          placeholder={supportAccounts.choices.length || supportAccounts.error ? 'Escolher conta' : 'Não há contas de suporte'}
          list={supportAccounts}
        />
        <TextField control={form.control} name="subject" label="Identificador da pessoa de suporte" maxLength={255} />
        <TextField
          control={form.control}
          name="durationMinutes"
          label="Duração (minutos)"
          type="number"
          inputMode="numeric"
          min={1}
          max={1440}
          className="max-w-32"
        />
        <FormField
          control={form.control}
          name="reason"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Motivo</FormLabel>
              <FormControl>
                <textarea
                  {...field}
                  rows={3}
                  maxLength={500}
                  className="w-full rounded-md border border-input bg-muted px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-invalid:border-destructive"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormFooter
          rootMessage={form.formState.errors.root?.message}
          generalError={command.generalError}
          pending={grant.isPending}
          onRetry={() => grant.variables && command.send(grant.variables)}
          onCancel={onClose}
          submitLabel="Conceder"
        />
      </form>
      {command.stepUpDialog}
    </Form>
  );
}
