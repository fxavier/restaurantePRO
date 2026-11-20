import { Button } from '@restaurantpro/ui';
import { Ban, CircleCheck, SlidersHorizontal } from 'lucide-react';
import { useCreditCommand } from '../hooks/use-credit-commands';
import { creditLimitSchema, optionalReasonSchema, reasonSchema } from '../schemas';
import { CommandDialog, TextField } from './command-dialog';

/** "Alterar limite" / "Definir limite": the first change opens the account, in the company's currency (D73). */
export function CreditLimitDialog({ customerId, label }: { customerId: string; label: 'Alterar limite' | 'Definir limite' }) {
  const runner = useCreditCommand(customerId, 'limit');
  return (
    <CommandDialog
      trigger={
        <Button variant={label === 'Definir limite' ? 'default' : 'outline'}>
          <SlidersHorizontal aria-hidden="true" />
          {label}
        </Button>
      }
      title="Alterar limite de crédito"
      description="O novo limite vale a partir de agora em todas as lojas. Fica registado com o motivo."
      submitLabel="Guardar limite"
      schema={creditLimitSchema}
      defaultValues={{ newLimit: '', reason: '' }}
      runner={runner}
      fields={(form) => (
        <>
          <TextField form={form} name="newLimit" label="Novo limite" inputMode="decimal" className="max-w-48 text-right tabular-nums" />
          <TextField form={form} name="reason" label="Motivo" maxLength={500} />
        </>
      )}
    />
  );
}

/** "Bloquear crédito": the POS refuses new credit sales until it is unblocked; the reason is kept. */
export function BlockCreditDialog({ customerId }: { customerId: string }) {
  const runner = useCreditCommand(customerId, 'block');
  return (
    <CommandDialog
      trigger={
        <Button variant="destructive">
          <Ban aria-hidden="true" />
          Bloquear crédito
        </Button>
      }
      title="Bloquear crédito"
      description="Enquanto estiver bloqueado, o cliente não pode comprar a crédito. A dívida mantém-se."
      submitLabel="Bloquear"
      schema={reasonSchema}
      defaultValues={{ reason: '' }}
      runner={runner}
      fields={(form) => <TextField form={form} name="reason" label="Motivo" maxLength={500} />}
    />
  );
}

/** "Desbloquear crédito": the reason is optional. */
export function UnblockCreditDialog({ customerId }: { customerId: string }) {
  const runner = useCreditCommand(customerId, 'unblock');
  return (
    <CommandDialog
      trigger={
        <Button variant="outline">
          <CircleCheck aria-hidden="true" />
          Desbloquear crédito
        </Button>
      }
      title="Desbloquear crédito"
      description="O cliente volta a poder comprar a crédito, dentro do limite."
      submitLabel="Desbloquear"
      schema={optionalReasonSchema}
      defaultValues={{ reason: '' }}
      runner={runner}
      fields={(form) => <TextField form={form} name="reason" label="Motivo (opcional)" maxLength={500} />}
    />
  );
}
