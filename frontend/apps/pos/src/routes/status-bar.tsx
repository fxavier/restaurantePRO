import { Button, type StatusTone, StatusPill } from '@restaurantpro/ui';
import { CircleUser, LoaderCircle, Monitor, RefreshCwOff, Store, Users, Wifi, WifiOff } from 'lucide-react';
import { PrintChip } from '../features/alerts/components/print-chip';
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

/** The connection chip: green "Ligado", red "Sem ligação", amber when only live updates are off, neutral meanwhile. */
const TONE = {
  open: 'success',
  connecting: 'neutral',
  down: 'destructive',
  refused: 'warning',
} as const satisfies Record<keyof typeof LABELS, StatusTone>;

/**
 * The POS status bar (DS layouts; light reference "Loja Baixa · POS-02 · Ana M."): the store, the terminal, the
 * employee, the pending prints (for who may read the print queue), the connection, and "Trocar empregado". "Ligado" means the change
 * stream is open, so the screen is current; "Sem ligação" that it is down (the screen is polled) or that Keycloak is;
 * "Atualizações indisponíveis" that the backend refused the stream (polled only). The section bar follows it, so every
 * screen of a session can reach every section.
 */
export function StatusBar() {
  const employee = useEmployee();
  const endSession = useEndSession();
  const connection = useConnection();
  const terminal = employee?.terminalCode ?? employee?.terminalName;

  return (
    <div className="flex flex-col gap-3 px-4 pt-4">
      {/* The light reference's top card: store · terminal · employee on the left; the pending prints, the connection chip
          and the switch on the right. */}
      <header className="flex min-h-16 flex-wrap items-center gap-x-4 gap-y-2 rounded-2xl border bg-card px-4 py-2">
        <h1 className="sr-only">restaurantPRO — Ponto de venda</h1>
        <p className="flex items-center gap-2 text-lg font-semibold">
          <Store aria-hidden="true" className="size-5 text-muted-foreground" />
          <span className="sr-only">Loja:</span>
          {employee?.storeName}
        </p>
        {terminal && (
          <p className="flex items-center gap-2 text-muted-foreground">
            <Monitor aria-hidden="true" className="size-5" />
            <span className="sr-only">Terminal:</span>
            <span>{terminal}</span>
          </p>
        )}
        <p className="flex items-center gap-2 text-muted-foreground">
          <CircleUser aria-hidden="true" className="size-5" />
          <span className="sr-only">Empregado:</span>
          {employee?.name}
        </p>
        <div className="ml-auto">
          <PrintChip />
        </div>
        {/* A fixed width, the chip at its end, so the button beside it never moves when the state changes (DS). */}
        <div className="flex w-64 justify-end">
          <StatusPill size="chip" tone={TONE[connection]} role="status" aria-label="Ligação">
            {connection === 'open' ? (
              <Wifi aria-hidden="true" />
            ) : connection === 'down' ? (
              <WifiOff aria-hidden="true" />
            ) : connection === 'refused' ? (
              <RefreshCwOff aria-hidden="true" />
            ) : (
              <LoaderCircle aria-hidden="true" />
            )}
            {LABELS[connection]}
          </StatusPill>
        </div>
        {/* The tinted destructive style for the action that hands the terminal over. */}
        <Button variant="destructive" size="touch" onClick={() => endSession('switch')}>
          <Users aria-hidden="true" />
          Trocar empregado
        </Button>
      </header>
      <PosSections />
    </div>
  );
}
