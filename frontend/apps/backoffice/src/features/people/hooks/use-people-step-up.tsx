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
import { useLocation } from 'react-router';
import { browser } from '../../../lib/browser';
import { stepUpUrl } from '../../../lib/step-up';

/**
 * The step-up hop for the people commands (FC §6, D184: every one of them is sensitive). Unlike the terminal commands,
 * nothing is kept for `/step-up/resume`: a PIN is a secret and is never stored, and the resume page knows only
 * terminal commands. The BFF brings the browser back to this tab, where the user repeats the command.
 */
export function usePeopleStepUp() {
  const { pathname, search } = useLocation();
  const [challenge, setChallenge] = useState<ApiError | null>(null);

  const dialog = (
    <AlertDialog open={challenge !== null} onOpenChange={(open) => !open && setChallenge(null)}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Confirme a sua identidade</AlertDialogTitle>
          <AlertDialogDescription>
            Alterar empregados, funções e acessos pede o segundo fator (o código da aplicação de autenticação). Depois
            de o confirmar volta a esta página e repete a operação: o que escreveu, como um PIN, não é guardado.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancelar</AlertDialogCancel>
          <AlertDialogAction onClick={() => challenge && browser.assign(stepUpUrl(challenge, `${pathname}${search}`))}>
            Confirmar identidade
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );

  return { prompt: setChallenge, dialog };
}
