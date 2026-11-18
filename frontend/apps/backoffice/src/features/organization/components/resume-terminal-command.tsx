import { Button, Card, CardContent, CardHeader, CardTitle, ConfirmButton } from '@restaurantpro/ui';
import { useNavigate } from 'react-router';
import { clearPendingCommand, isStepUpRequired } from '../../../lib/step-up';
import { useIssueEnrolmentCode, useRevokeTerminal } from '../hooks/use-terminal-commands';
import { useTerminalStepUp } from '../hooks/use-step-up-command';
import { useStoreTimeZone } from '../hooks/use-store-time-zone';
import type { TerminalCommand } from '../terminal-commands';
import { CommandError } from './command-error';
import { EnrolmentCodeDialog } from './enrolment-code-dialog';
import { RegisterTerminalForm } from './register-terminal-form';

type ResumeProps = { command: TerminalCommand; returnTo: string };

/**
 * Back from a step-up (story 18, FC §6): the interrupted terminal command with its input kept, and "Continuar", which
 * sends the same request. The stored command is removed as soon as "Continuar" sends it, so a reload can never replay
 * it; the restored context stays in memory for "Tentar novamente".
 */
export function ResumeTerminalCommand({ command, returnTo }: ResumeProps) {
  const navigate = useNavigate();
  const finish = () => {
    clearPendingCommand();
    navigate(returnTo);
  };
  const cancel = (
    <Button type="button" variant="outline" onClick={finish}>
      Cancelar
    </Button>
  );

  if (command.type === 'create') {
    return (
      <Card className="mt-6 max-w-xl">
        <CardHeader>
          <CardTitle>Registar terminal</CardTitle>
        </CardHeader>
        <CardContent>
          <RegisterTerminalForm
            storeId={command.body.storeId}
            returnTo={returnTo}
            submitLabel="Continuar"
            defaultValues={{ code: command.body.code, name: command.body.name }}
            onSend={clearPendingCommand}
            idempotencyKey={command.idempotencyKey}
            onCreated={finish}
          >
            {cancel}
          </RegisterTerminalForm>
        </CardContent>
      </Card>
    );
  }
  return <ResumeTerminalAction command={command} returnTo={returnTo} cancel={cancel} onDone={finish} />;
}

type ActionProps = {
  command: Extract<TerminalCommand, { type: 'issueCode' | 'revoke' }>;
  returnTo: string;
  cancel: React.ReactNode;
  onDone: () => void;
};

function ResumeTerminalAction({ command, returnTo, cancel, onDone }: ActionProps) {
  const issue = useIssueEnrolmentCode();
  const revoke = useRevokeTerminal();
  const stepUp = useTerminalStepUp(returnTo);
  const timeZone = useStoreTimeZone(new URLSearchParams(returnTo.split('?')[1]).get('storeId') ?? undefined);
  const mutation = command.type === 'issueCode' ? issue : revoke;
  const target = { terminalId: command.terminalId, terminalName: command.terminalName };

  const send = () => {
    const onError = (error: Error) => isStepUpRequired(error) && stepUp.prompt(error, command);
    clearPendingCommand();
    if (command.type === 'issueCode') {
      // The answer is the code; the page leaves when its dialog closes.
      issue.mutate(target, { onError });
    } else {
      revoke.mutate({ ...target, idempotencyKey: command.idempotencyKey }, { onSuccess: onDone, onError });
    }
  };

  return (
    <Card className="mt-6 max-w-xl">
      <CardHeader>
        <CardTitle>{command.type === 'issueCode' ? 'Emitir código de registo' : 'Revogar terminal'}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p>
          {command.type === 'issueCode'
            ? `Emitir o código de registo do terminal ${command.terminalName}.`
            : `Revogar o terminal ${command.terminalName}.`}
        </p>
        {mutation.error && !isStepUpRequired(mutation.error) && (
          <CommandError error={mutation.error} pending={mutation.isPending} onRetry={send} />
        )}
        <div className="flex flex-wrap justify-end gap-2">
          {cancel}
          <ConfirmButton
            variant={command.type === 'revoke' ? 'destructive' : 'default'}
            pending={mutation.isPending}
            disabled={issue.isSuccess}
            onClick={send}
          >
            Continuar
          </ConfirmButton>
        </div>
      </CardContent>
      <EnrolmentCodeDialog
        issued={issue.data}
        terminalName={command.terminalName}
        timeZone={timeZone}
        onClose={() => {
          issue.reset();
          onDone();
        }}
      />
      {stepUp.dialog}
    </Card>
  );
}
