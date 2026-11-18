import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
  Button,
} from '@restaurantpro/ui';
import { ShieldX } from 'lucide-react';
import { PosScreen } from './pos-screen';

/**
 * Keycloak refused this terminal's client (D182: revoked, or a skewed clock or a misconfiguration). The key is never
 * erased automatically; only the confirmed "Registar de novo" does it, so a fixable cause does not un-enrol terminals.
 */
export function RevokedState({ onReEnrol }: { onReEnrol: () => void }) {
  return (
    <PosScreen>
      <section
        aria-labelledby="revoked-title"
        className="flex w-full max-w-xl flex-col gap-4 rounded-xl border bg-background p-6"
      >
        <div className="flex items-center gap-3">
          <ShieldX aria-hidden="true" className="size-8 shrink-0 text-destructive" />
          <h2 id="revoked-title" className="text-2xl font-semibold">
            Terminal revogado ou inválido
          </h2>
        </div>
        <p>
          O servidor de identidade recusou este terminal. Um administrador tem de emitir um novo código de registo no
          BackOffice. Se o terminal não foi revogado, verifique primeiro a data e a hora deste dispositivo.
        </p>
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="outline" size="touch" className="self-start">
              Registar de novo
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Registar este terminal de novo?</AlertDialogTitle>
              <AlertDialogDescription>
                A chave deste terminal é apagada e será preciso um novo código de registo. Não é possível desfazer.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel size="touch">Cancelar</AlertDialogCancel>
              <AlertDialogAction variant="destructive" size="touch" onClick={onReEnrol}>
                Apagar e registar de novo
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </section>
    </PosScreen>
  );
}
