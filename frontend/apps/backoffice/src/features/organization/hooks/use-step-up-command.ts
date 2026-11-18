import type { ApiError } from '@restaurantpro/api-client';
import { useSession } from '../../identity';
import { useStepUpPrompt } from '../../../lib/step-up-dialog';
import { toPendingCommand, type TerminalCommand } from '../terminal-commands';

/**
 * The step-up prompt for terminal commands: `prompt(error, command)` keeps the command for the company the session
 * is in and returns to `returnTo` once it has run (FC §6).
 */
export function useTerminalStepUp(returnTo: string) {
  const { data: session } = useSession();
  const { prompt, dialog } = useStepUpPrompt();
  return {
    dialog,
    prompt: (error: ApiError, command: TerminalCommand) =>
      prompt(error, toPendingCommand(command, returnTo, session?.tenantId ?? '')),
  };
}
