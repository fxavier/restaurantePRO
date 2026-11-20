import { Button } from '@restaurantpro/ui';
import { CircleUser, LoaderCircle, RefreshCwOff, Store, Users, Wifi, WifiOff } from 'lucide-react';
import { useEmployee } from '../lib/auth/use-employee';
import { useEndSession } from '../lib/auth/use-end-session';
import { useConnection } from '../lib/live/use-connection';
import { PosSections } from './pos-sections';

const LABELS = {
  open: 'Ligado',
  connecting: 'A ligar…',
  down: 'Sem ligação',
  // The screen is still read every 15 s, so it is not "no connection"; live updates alone are off.
  refused: 'Atualizações indisponíveis',
} as const;

/**
 * The POS status bar (DS layouts): the store, the employee, the connection, and "Trocar empregado". "Ligado" means the change
 * stream is open, so the screen is current; "Sem ligação" that it is down (the screen is polled) or that Keycloak is;
 * "Atualizações indisponíveis" that the backend refused the stream (polled only). The section bar follows it, so every
 * screen of a session can reach every section.
 */
export function StatusBar() {
  const employee = useEmployee();
  const endSession = useEndSession();
  const connection = useConnection();

  return (
    <>
      <header className="flex min-h-16 flex-wrap items-center gap-x-6 gap-y-2 border-b bg-card px-4 py-2">
        <h1 className="text-base font-semibold text-muted-foreground">restaurantPRO — Ponto de venda</h1>
        <p className="flex items-center gap-2 text-lg font-semibold">
          <Store aria-hidden="true" className="size-6" />
          <span className="sr-only">Loja:</span>
          {employee?.storeName}
        </p>
        <p className="flex items-center gap-2 font-medium">
          <CircleUser aria-hidden="true" className="size-6" />
          <span className="sr-only">Empregado:</span>
          {employee?.name}
        </p>
        {/* A fixed width, so the button beside it never moves when the state changes (DS: no layout shift). */}
        <p role="status" aria-label="Ligação" className="flex w-60 items-center gap-2 text-muted-foreground">
          {connection === 'open' ? (
            <Wifi aria-hidden="true" className="size-6 text-success" />
          ) : connection === 'down' ? (
            <WifiOff aria-hidden="true" className="size-6 text-error" />
          ) : connection === 'refused' ? (
            <RefreshCwOff aria-hidden="true" className="size-6" />
          ) : (
            <LoaderCircle aria-hidden="true" className="size-6" />
          )}
          {LABELS[connection]}
        </p>
        {/* Stitch's red "Trancar Terminal": the same tinted destructive style for the action that hands the terminal over. */}
        <Button variant="destructive" size="touch" className="ml-auto" onClick={() => endSession('switch')}>
          <Users aria-hidden="true" />
          Trocar empregado
        </Button>
      </header>
      <PosSections />
    </>
  );
}
