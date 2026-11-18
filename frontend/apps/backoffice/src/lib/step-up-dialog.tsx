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
import { browser } from './browser';
import { RESUME_PATH, savePendingCommand, stepUpUrl, type PendingCommand } from './step-up';

type Prompt = { error: ApiError; command: Omit<PendingCommand, 'savedAt'> };

/**
 * The step-up hop (FC §6, story 17): `prompt` opens "Confirme a sua identidade"; on confirm the command is kept in
 * sessionStorage and the browser goes to the BFF's step-up, which brings it back to the resume page.
 */
export function useStepUpPrompt() {
  const [pending, setPending] = useState<Prompt | null>(null);

  const confirm = () => {
    if (!pending) return;
    savePendingCommand(pending.command);
    browser.assign(stepUpUrl(pending.error, RESUME_PATH));
  };

  const dialog = (
    <AlertDialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Confirme a sua identidade</AlertDialogTitle>
          <AlertDialogDescription>
            Esta ação é sensível e pede que confirme a sua identidade com o segundo fator (o código da aplicação de
            autenticação). Vai à página de início de sessão e volta aqui, com a operação pronta a continuar.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction onClick={confirm}>Confirmar identidade</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );

  return { prompt: (error: ApiError, command: Prompt['command']) => setPending({ error, command }), dialog };
}
