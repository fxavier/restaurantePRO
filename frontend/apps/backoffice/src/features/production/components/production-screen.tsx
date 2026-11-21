import type { UseQueryResult } from '@tanstack/react-query';
import { useSession } from '../../identity';
import { usePrintJobs } from '../hooks/use-print-jobs';
import { usePrinters } from '../hooks/use-printers';
import { useRoutes, useTerminalPrinters } from '../hooks/use-routing';
import { useStoreTerminals } from '../hooks/use-store-terminals';
import { useStoreTimeZone } from '../hooks/use-store-time-zone';
import { useUnroutedTickets, type TicketPage } from '../hooks/use-tickets';
import { nameOf, type Printer } from '../parse';
import { holdsIn } from '../permissions';
import { PrintQueue } from './print-queue';
import { PrinterList } from './printer-list';
import { ProductionSummary } from './production-summary';
import { RoutingMatrix } from './routing-matrix';
import { UnroutedTickets } from './unrouted-tickets';

/** The store's production setup (§5, §8.6): what needs someone, the printers, the print queue and the routing. */
export function ProductionScreen({ storeId }: { storeId: string }) {
  const permissions = useSession().data?.permissions ?? [];
  const printers = usePrinters(storeId);
  const routes = useRoutes(storeId);
  const assignments = useTerminalPrinters(storeId);
  const terminals = useStoreTerminals(storeId);
  const tickets = useUnroutedTickets(storeId);
  const timeZone = useStoreTimeZone(storeId);
  const mayOperate = holdsIn(permissions, 'PRODUCTION_OPERATE', storeId);

  return (
    <>
      <Summary storeId={storeId} printers={printers} tickets={tickets} />
      <div className="mt-6 grid items-start gap-6 lg:grid-cols-5">
        <PrinterList
          storeId={storeId}
          printers={printers}
          routes={routes.data ?? []}
          assignments={assignments.data ?? []}
          terminalName={(id) => nameOf(terminals.data, id, 'Terminal sem nome')}
          maySuppress={holdsIn(permissions, 'PRODUCTION_SUPPRESS', storeId)}
        />
        <div className="flex flex-col gap-6 lg:col-span-2">
          <PrintQueue
            storeId={storeId}
            timeZone={timeZone}
            printerName={(id) => nameOf(printers.data, id, 'Impressora desconhecida')}
            mayOperate={mayOperate}
          />
          <UnroutedTickets tickets={tickets} mayOperate={mayOperate} />
        </div>
        {printers.data && (
          <RoutingMatrix
            storeId={storeId}
            printers={printers.data}
            routes={routes}
            assignments={assignments}
            terminals={terminals}
          />
        )}
      </div>
    </>
  );
}

type SummaryProps = { storeId: string; printers: UseQueryResult<Printer[]>; tickets: UseQueryResult<TicketPage> };

/**
 * The counts are the server's totals of a one-row page, never a count of what a list page happens to hold. A resolved
 * job keeps its FAILED or UNKNOWN status, so these totals include resolved jobs (no "unresolved" filter yet).
 */
function Summary({ storeId, printers, tickets }: SummaryProps) {
  const failed = usePrintJobs(storeId, 'FAILED', 0, 1);
  const unknown = usePrintJobs(storeId, 'UNKNOWN', 0, 1);
  return (
    <ProductionSummary
      activePrinters={printers.data?.filter((printer) => printer.active).length}
      printers={printers.data?.length}
      failed={failed.data?.totalElements}
      unknown={unknown.data?.totalElements}
      unrouted={tickets.data?.totalElements}
    />
  );
}
