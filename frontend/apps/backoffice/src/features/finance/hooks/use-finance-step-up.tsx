import type { ApiError } from '@restaurantpro/api-client';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@restaurantpro/ui';
import { useState } from 'react';
import { browser } from '../../../lib/browser';
import { savePendingCommand, stepUpUrl } from '../../../lib/step-up';
import { useTenantPin } from '../../../lib/tenant-pin';
import { commandTab, financePath, toPendingCommand, type FinanceCommand } from '../finance-commands';

type Prompt = { error: ApiError; command: FinanceCommand };

/**
 * The step-up hop for a finance command (FC §6): "Confirme a sua identidade", then the command is kept for the company
 * this tab is pinned to (FE-F16) and the browser goes to the BFF's step-up, which brings it back to the command's tab,
 * where "Continuar" sends it again.
 */
export function useFinanceStepUp(storeId: string) {
  const tenantPin = useTenantPin();
  const [pending, setPending] = useState<Prompt | null>(null);

  const confirm = () => {
    if (!pending) return;
    savePendingCommand(toPendingCommand(pending.command, storeId, tenantPin.get()?.tenantId ?? ''));
    browser.assign(stepUpUrl(pending.error, financePath(storeId, commandTab(pending.command))));
  };

  const dialog = (
    <AlertDialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Confirme a sua identidade</AlertDialogTitle>
          <AlertDialogDescription>
            Esta operação é sensível e pede o segundo fator (o código da aplicação de autenticação). Vai à página de
            início de sessão e volta a este separador, com a operação pronta a continuar.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction onClick={confirm}>Confirmar identidade</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );

  return { prompt: (error: ApiError, command: FinanceCommand) => setPending({ error, command }), dialog };
}
