import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Button,
  LOCALE,
  StatusBadge,
  TableCell,
  TableRow,
} from '@restaurantpro/ui';
import { Ban, LifeBuoy } from 'lucide-react';
import { useState } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import { useRevokeSupport } from '../hooks/use-people-commands';
import { usePeopleStepUp } from '../hooks/use-people-step-up';
import { useSupportAccounts } from '../hooks/use-choices';
import { useSupportGrants } from '../hooks/use-people-queries';
import { useDisplayTimeZone } from '../hooks/use-time-zone';
import type { SupportGrantSummary } from '../types';
import { CommandError } from './command-error';
import { FormDialog } from './form-dialog';
import { GrantForm } from './grant-form';
import { grantStatuses } from './labels';
import { ListCard } from './list-card';
import { ListTable } from './list-table';

type Revocation = { grantId: string; idempotencyKey: string };

/**
 * "Acessos de suporte" (D83): the time-boxed access the company grants to a support account, and its revocation. Both
 * commands carry an Idempotency-Key, one per intent (FC §8).
 */
export function SupportTab() {
  const [page, setPage] = useState(0);
  const grants = useSupportGrants(page);
  const { supportAccounts, usernameOf } = useSupportAccounts();
  const timeZone = useDisplayTimeZone();
  const revoke = useRevokeSupport();
  const stepUp = usePeopleStepUp();
  const [granting, setGranting] = useState(false);
  const [toRevoke, setToRevoke] = useState<SupportGrantSummary | null>(null);

  const revokeOne = (revocation: Revocation) =>
    revoke.mutate(revocation, { onError: (error) => isStepUpRequired(error) && stepUp.prompt(error) });

  return (
    <div className="flex flex-col gap-4">
      <CommandError error={revoke.error} pending={revoke.isPending} onRetry={() => revoke.variables && revokeOne(revoke.variables)} />
      <ListCard
        title="Acessos de suporte"
        description="O suporte só entra na empresa durante um acesso concedido aqui, por no máximo 24 horas."
        actions={
          <Button onClick={() => setGranting(true)}>
            <LifeBuoy aria-hidden="true" />
            Conceder acesso
          </Button>
        }
        query={grants}
        what="os acessos de suporte"
        emptyMessage="Nenhum acesso de suporte concedido."
        onPage={setPage}
      >
        {(rows) => (
          <ListTable caption="Acessos de suporte" headers={['Conta', 'Motivo', 'Válido até', 'Estado']}>
            {rows.map((grant) => (
              <GrantRow
                key={grant.id}
                grant={grant}
                username={usernameOf(grant.accountId)}
                validUntil={formatInstant(grant.validUntil, timeZone)}
                revoking={revoke.isPending}
                onRevoke={() => setToRevoke(grant)}
              />
            ))}
          </ListTable>
        )}
      </ListCard>
      <FormDialog
        title="Conceder acesso de suporte"
        description="A pessoa de suporte entra com a conta escolhida até o acesso expirar ou ser revogado."
        open={granting}
        onClose={() => setGranting(false)}
      >
        <GrantForm supportAccounts={supportAccounts} onClose={() => setGranting(false)} />
      </FormDialog>
      <RevokeGrantDialog
        username={toRevoke ? usernameOf(toRevoke.accountId) : null}
        onCancel={() => setToRevoke(null)}
        // One revocation intent: a new key here, the same one for "Tentar novamente" (FC §8).
        onConfirm={() => revokeOne({ grantId: toRevoke?.id ?? '', idempotencyKey: crypto.randomUUID() })}
      />
      {stepUp.dialog}
    </div>
  );
}

type GrantRowProps = {
  grant: SupportGrantSummary;
  username: string;
  validUntil: string;
  revoking: boolean;
  onRevoke: () => void;
};

/** One grant: the account, why, until when, its state, and "Revogar" while it is active. */
function GrantRow({ grant, username, validUntil, revoking, onRevoke }: GrantRowProps) {
  return (
    <TableRow>
      <TableCell className="font-mono">{username}</TableCell>
      <TableCell className="whitespace-normal">{grant.reason}</TableCell>
      <TableCell className="tabular-nums">{validUntil}</TableCell>
      <TableCell>{grant.status && <StatusBadge status={grant.status} statuses={grantStatuses} />}</TableCell>
      <TableCell>
        {grant.status === 'ACTIVE' && (
          <div className="flex justify-end">
            <Button variant="outline" size="sm" aria-label={`Revogar o acesso de ${username}`} disabled={revoking} onClick={onRevoke}>
              <Ban aria-hidden="true" />
              Revogar
            </Button>
          </div>
        )}
      </TableCell>
    </TableRow>
  );
}

type RevokeGrantDialogProps = { username: string | null; onConfirm: () => void; onCancel: () => void };

/** Revoking ends the support person's access at once, so it is confirmed first. */
function RevokeGrantDialog({ username, onConfirm, onCancel }: RevokeGrantDialogProps) {
  return (
    <AlertDialog open={username !== null} onOpenChange={(open) => !open && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Revogar o acesso de {username}?</AlertDialogTitle>
          <AlertDialogDescription>A pessoa de suporte deixa de entrar na empresa de imediato.</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm}>
            Revogar
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function formatInstant(iso: string | undefined, timeZone: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(iso));
}
