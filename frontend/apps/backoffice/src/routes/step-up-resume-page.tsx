import { useState } from 'react';
import { Link } from 'react-router';
import { useSession } from '../features/identity';
import { fromPendingCommand, ResumeTerminalCommand } from '../features/organization';
import { clearPendingCommand, readPendingCommand } from '../lib/step-up';

/**
 * `/step-up/resume` (FC §4, §6): where the BFF returns after a step-up. A stored command that is malformed, old, of
 * another company or not a resumable command is discarded; the user then starts again.
 */
export function StepUpResumePage() {
  const { data: session } = useSession();
  const [resumable] = useState(() => {
    const pending = readPendingCommand(session?.tenantId);
    const command = pending && fromPendingCommand(pending);
    if (!pending || !command) {
      clearPendingCommand();
      return undefined;
    }
    return { command, returnTo: pending.returnTo };
  });

  return (
    <>
      <h1 className="text-2xl font-semibold">Continuar a operação</h1>
      {resumable ? (
        <>
          <p className="mt-1 text-muted-foreground">Identidade confirmada. Reveja e continue.</p>
          <ResumeTerminalCommand command={resumable.command} returnTo={resumable.returnTo} />
        </>
      ) : (
        <div className="mt-6 flex flex-col gap-2">
          <p>Não há nenhuma operação por continuar.</p>
          <p>
            <Link to="/" className="text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-ring">
              Voltar à visão geral
            </Link>
          </p>
        </div>
      )}
    </>
  );
}
