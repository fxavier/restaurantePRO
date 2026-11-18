import { Button } from '@restaurantpro/ui';
import { CircleUser, LoaderCircle, RefreshCwOff, Users, Wifi, WifiOff } from 'lucide-react';
import { useEmployee } from '../lib/auth/use-employee';
import { useEndSession } from '../lib/auth/use-end-session';
import { useConnection } from '../lib/live/use-connection';

const LABELS = {
  open: 'Ligado',
  connecting: 'A ligar…',
  down: 'Sem ligação',
  // The screen is still read every 15 s, so it is not "no connection"; live updates alone are off.
  refused: 'Atualizações indisponíveis',
} as const;

/**
 * The POS status bar (DS layouts): the employee, the connection, and "Trocar empregado". "Ligado" means the change
 * stream is open, so the screen is current; "Sem ligação" that it is down (the screen is polled) or that Keycloak is;
 * "Atualizações indisponíveis" that the backend refused the stream (polled only).
 */
export function StatusBar() {
  const employee = useEmployee();
  const endSession = useEndSession();
  const connection = useConnection();

  return (
    <header className="flex h-16 items-center gap-6 border-b bg-background px-4">
      <h1 className="text-lg font-semibold">restaurantPRO — Ponto de venda</h1>
      <p className="flex items-center gap-2 font-medium">
        <CircleUser aria-hidden="true" className="size-6" />
        <span className="sr-only">Empregado:</span>
        {employee?.name}
      </p>
      {/* A fixed width, so the button beside it never moves when the state changes (DS: no layout shift). */}
      <p role="status" aria-label="Ligação" className="flex w-60 items-center gap-2 text-muted-foreground">
        {connection === 'open' ? (
          <Wifi aria-hidden="true" className="size-6" />
        ) : connection === 'down' ? (
          <WifiOff aria-hidden="true" className="size-6 text-destructive" />
        ) : connection === 'refused' ? (
          <RefreshCwOff aria-hidden="true" className="size-6" />
        ) : (
          <LoaderCircle aria-hidden="true" className="size-6" />
        )}
        {LABELS[connection]}
      </p>
      <Button variant="outline" size="touch" className="ml-auto" onClick={() => endSession('switch')}>
        <Users aria-hidden="true" />
        Trocar empregado
      </Button>
    </header>
  );
}
