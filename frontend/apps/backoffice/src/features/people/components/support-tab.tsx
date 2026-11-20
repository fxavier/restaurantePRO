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
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Ban, LifeBuoy } from 'lucide-react';
import { useState } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import { useRevokeSupport } from '../hooks/use-people-commands';
import { usePeopleStepUp } from '../hooks/use-people-step-up';
import { useSupportGrants, useUsers } from '../hooks/use-people-queries';
import { useDisplayTimeZone } from '../hooks/use-time-zone';
import type { SupportGrantSummary } from '../types';
import { CommandError } from './command-error';
import { FormDialog } from './form-dialog';
import { GrantForm } from './grant-form';
import { grantStatuses } from './labels';
import { ListCard } from './list-card';

type Revocation = { grantId: string; idempotencyKey: string };

/**
 * "Acessos de suporte" (D83): the time-boxed access the company grants to a support account, and its revocation. Both
 * commands carry an Idempotency-Key, one per intent (FC §8).
 */
export function SupportTab() {
  const [page, setPage] = useState(0);
  const grants = useSupportGrants(page);
  // The support accounts, to name each grant's account and to choose one; one page holds a company's few accounts.
  const accounts = useUsers(0).data?.rows ?? [];
  const usernameOf = (id: string | undefined) => accounts.find((account) => account.id === id)?.username ?? id ?? '';
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
          <Table>
            <TableCaption className="sr-only">Acessos de suporte</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Conta</TableHead>
                <TableHead>Motivo</TableHead>
                <TableHead>Válido até</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((grant) => (
                <TableRow key={grant.id}>
                  <TableCell className="font-mono">{usernameOf(grant.accountId)}</TableCell>
                  <TableCell className="whitespace-normal">{grant.reason}</TableCell>
                  <TableCell className="tabular-nums">{formatInstant(grant.validUntil, timeZone)}</TableCell>
                  <TableCell>{grant.status && <StatusBadge status={grant.status} statuses={grantStatuses} />}</TableCell>
                  <TableCell>
                    {grant.status === 'ACTIVE' && (
                      <div className="flex justify-end">
                        <Button
                          variant="outline"
                          size="sm"
                          aria-label={`Revogar o acesso de ${usernameOf(grant.accountId)}`}
                          disabled={revoke.isPending}
                          onClick={() => setToRevoke(grant)}
                        >
                          <Ban aria-hidden="true" />
                          Revogar
                        </Button>
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </ListCard>
      <FormDialog
        title="Conceder acesso de suporte"
        description="A pessoa de suporte entra com a conta escolhida até o acesso expirar ou ser revogado."
        open={granting}
        onClose={() => setGranting(false)}
      >
        <GrantForm supportAccounts={accounts.filter((account) => account.support)} onClose={() => setGranting(false)} />
      </FormDialog>
      <AlertDialog open={toRevoke !== null} onOpenChange={(open) => !open && setToRevoke(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Revogar o acesso de {usernameOf(toRevoke?.accountId)}?</AlertDialogTitle>
            <AlertDialogDescription>A pessoa de suporte deixa de entrar na empresa de imediato.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => toRevoke && revokeOne({ grantId: toRevoke.id ?? '', idempotencyKey: crypto.randomUUID() })}
            >
              Revogar
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {stepUp.dialog}
    </div>
  );
}

function formatInstant(iso: string | undefined, timeZone: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(iso));
}
