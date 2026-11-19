import { ApiError } from '@restaurantpro/api-client';
import {
  Button,
  DataState,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Ban, KeyRound, Lock, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { useLocation } from 'react-router';
import { browser } from '../../../lib/browser';
import { isUnavailable } from '../../../lib/errors';
import { isStepUpRequired, stepUpUrl } from '../../../lib/step-up';
import { useIssueEnrolmentCode, useRevokeTerminal } from '../hooks/use-terminal-commands';
import { TERMINAL_PAGE_SIZE, useTerminals } from '../hooks/use-terminals';
import { useTerminalStepUp } from '../hooks/use-step-up-command';
import { useStoreTimeZone } from '../hooks/use-store-time-zone';
import type { TerminalSummary } from '../types';
import { CommandError } from './command-error';
import { EnrolmentCodeDialog } from './enrolment-code-dialog';
import { RegisterTerminalDialog } from './register-terminal-dialog';
import { RevokeTerminalDialog } from './revoke-terminal-dialog';
import { kindLabel, terminalStatuses } from './terminal-labels';

type Target = { terminalId: string; terminalName: string };
type Revocation = Target & { idempotencyKey: string };

/** The selected store's terminals (stories 12–16): the list, and register, issue a code and revoke. */
export function StoreTerminals({ storeId }: { storeId: string }) {
  const { pathname, search } = useLocation();
  const returnTo = `${pathname}${search}`;
  const [page, setPage] = useState(0);
  const terminals = useTerminals(storeId, page);
  const issue = useIssueEnrolmentCode();
  const revoke = useRevokeTerminal();
  const stepUp = useTerminalStepUp(returnTo);
  const timeZone = useStoreTimeZone(storeId);
  const [toRevoke, setToRevoke] = useState<TerminalSummary | null>(null);

  const issueCode = (target: Target) =>
    issue.mutate(target, {
      onError: (error) => isStepUpRequired(error) && stepUp.prompt(error, { type: 'issueCode', ...target }),
    });
  /** One revocation intent, under one key for every retry and the step-up resume (FC §8); a code issue has none. */
  const revokeOne = (revocation: Revocation) =>
    revoke.mutate(revocation, {
      onError: (error) => isStepUpRequired(error) && stepUp.prompt(error, { type: 'revoke', ...revocation }),
    });
  const target = (terminal: TerminalSummary): Target => ({ terminalId: terminal.id, terminalName: terminal.name });

  if (terminals.isError) return <ListError error={terminals.error} returnTo={returnTo} onRetry={() => void terminals.refetch()} />;

  const data = terminals.data;
  const pages = data ? Math.ceil(data.totalElements / TERMINAL_PAGE_SIZE) : 0;
  return (
    <div className="mt-6 flex flex-col gap-4">
      {data && (
        <div className="flex justify-end">
          <RegisterTerminalDialog storeId={storeId} returnTo={returnTo} />
        </div>
      )}
      {issue.error && !isStepUpRequired(issue.error) && (
        <CommandError error={issue.error} pending={issue.isPending} onRetry={() => issue.variables && issueCode(issue.variables)} />
      )}
      {revoke.error && !isStepUpRequired(revoke.error) && (
        <CommandError error={revoke.error} pending={revoke.isPending} onRetry={() => revoke.variables && revokeOne(revoke.variables)} />
      )}
      <DataState
        state={terminals.isPending ? 'loading' : data?.terminals.length ? 'ready' : 'empty'}
        emptyMessage="Esta loja ainda não tem terminais."
      >
        <Table>
          <TableCaption className="sr-only">Terminais da loja</TableCaption>
          <TableHeader>
            <TableRow>
              <TableHead>Nome</TableHead>
              <TableHead>Código</TableHead>
              <TableHead>Tipo</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead>
                <span className="sr-only">Ações</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data?.terminals.map((terminal) => (
              <TableRow key={terminal.id}>
                <TableCell className="font-medium">{terminal.name}</TableCell>
                <TableCell className="font-mono">{terminal.code}</TableCell>
                <TableCell>{kindLabel(terminal.kind)}</TableCell>
                <TableCell>
                  <StatusBadge status={terminal.status} statuses={terminalStatuses(terminal.status)} />
                </TableCell>
                <TableCell>
                  {terminal.status !== 'REVOKED' && (
                    <div className="flex justify-end gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        aria-label={`Emitir código para ${terminal.name}`}
                        disabled={issue.isPending}
                        onClick={() => issueCode(target(terminal))}
                      >
                        <KeyRound aria-hidden="true" />
                        Emitir código
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        aria-label={`Revogar ${terminal.name}`}
                        disabled={revoke.isPending}
                        onClick={() => setToRevoke(terminal)}
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
        {pages > 1 && (
          <nav aria-label="Páginas" className="flex items-center justify-end gap-2 text-sm">
            <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Anterior
            </Button>
            <span>
              Página {page + 1} de {pages}
            </span>
            <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
              Seguinte
            </Button>
          </nav>
        )}
      </DataState>
      <RevokeTerminalDialog
        terminal={toRevoke}
        onCancel={() => setToRevoke(null)}
        onConfirm={(terminal) => {
          setToRevoke(null);
          revokeOne({ ...target(terminal), idempotencyKey: crypto.randomUUID() });
        }}
      />
      <EnrolmentCodeDialog
        issued={issue.data}
        terminalName={issue.variables?.terminalName ?? ''}
        timeZone={timeZone}
        onClose={() => issue.reset()}
      />
      {stepUp.dialog}
    </div>
  );
}

/** The list's own failures: a step-up to read it, no permission (AC10), no connection, anything else. */
function ListError({ error, returnTo, onRetry }: { error: unknown; returnTo: string; onRetry: () => void }) {
  if (isStepUpRequired(error)) {
    return (
      <Notice icon={ShieldCheck} text="Para ver os terminais, confirme a sua identidade.">
        <Button onClick={() => browser.assign(stepUpUrl(error, returnTo))}>Confirmar identidade</Button>
      </Notice>
    );
  }
  if (error instanceof ApiError && error.status === 403) {
    return <Notice icon={Lock} text="Sem permissão para ver os terminais desta loja." />;
  }
  return (
    <div className="mt-6">
      <DataState state={isUnavailable(error) ? 'unavailable' : 'error'} onRetry={onRetry}>
        {null}
      </DataState>
    </div>
  );
}

function Notice({ icon: Icon, text, children }: { icon: typeof Lock; text: string; children?: React.ReactNode }) {
  return (
    <div className="mt-6 flex flex-col items-center gap-3 rounded-lg border p-8 text-center">
      <Icon aria-hidden="true" className="size-8 text-muted-foreground" />
      <p>{text}</p>
      {children}
    </div>
  );
}
