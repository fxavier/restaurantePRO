import type { ApiError } from '@restaurantpro/api-client';
import { useStepUpPrompt } from '../../../lib/step-up-dialog';
import { useTenantPin } from '../../../lib/tenant-pin';
import { toPendingCommand, type TerminalCommand } from '../terminal-commands';

/**
 * The step-up prompt for terminal commands: `prompt(error, command)` keeps the command for the company this tab is
 * pinned to (FE-F16) and returns to `returnTo` once it has run (FC §6).
 */
export function useTerminalStepUp(returnTo: string) {
  const tenantPin = useTenantPin();
  const { prompt, dialog } = useStepUpPrompt();
  return {
    dialog,
    prompt: (error: ApiError, command: TerminalCommand) =>
      prompt(error, toPendingCommand(command, returnTo, tenantPin.get()?.tenantId ?? '')),
  };
}
