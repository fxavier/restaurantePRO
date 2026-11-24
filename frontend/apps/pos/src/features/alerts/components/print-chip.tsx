import { Printer } from 'lucide-react';
import { useEmployee } from '../../../lib/auth/use-employee';
import { maySeeProductionAlerts, pendingPrintsWords } from '../alerts';
import { useProductionAlerts } from '../hooks/use-alerts';

/**
 * The status bar's amber "1 impressão pendente" (light reference), while the store has prints waiting or failed. Not
 * a link: the POS has no print queue screen (the BackOffice does). A live region, so a new one is announced; nothing
 * at all for a session that may not read the print queue.
 */
export function PrintChip() {
  const employee = useEmployee();
  // The query only for who may see it: nothing is read, or cached, for anyone else.
  return maySeeProductionAlerts(employee) ? <PendingPrints /> : null;
}

function PendingPrints() {
  const alerts = useProductionAlerts();
  const pending = alerts.data?.pendingPrintJobs ?? 0;
  return (
    <div role="status" aria-label="Impressões">
      {pending > 0 && (
        <p className="flex items-center gap-2 rounded-xl bg-warning-tint px-3 py-2 text-sm font-medium text-warning-text">
          <Printer aria-hidden="true" className="size-4" />
          {/* At 1024 px the status bar keeps one row only with the count alone; the words stay for assistive technology. */}
          <span>
            <span className="tabular-nums">{pending}</span>
            <span className="sr-only xl:not-sr-only"> {pendingPrintsWords(pending)}</span>
          </span>
        </p>
      )}
    </div>
  );
}
