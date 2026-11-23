import { ChefHat, CircleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { useEmployee } from '../../../lib/auth/use-employee';
import { lateRoundsLabel, maySeePaymentAlerts, maySeeProductionAlerts, paymentsToConfirmLabel } from '../alerts';
import { useProductionAlerts, useSettlementAlerts } from '../hooks/use-alerts';

const PAYMENT = 'bg-destructive-tint text-destructive-text';
const KITCHEN = 'bg-warning-tint text-warning-text';

/**
 * The footer's alerts (light reference): the payments in doubt, red, to the payment screen of the oldest one's order
 * (none for a debt payment, which has no order); the late kitchen rounds, amber, to the kitchen display. Each only
 * for a session that may see it, and only when there is one. A live region, so a new alert is announced.
 */
export function AlertChips() {
  const employee = useEmployee();
  // Each query only for who may see it: nothing is read, or cached, for anyone else.
  return (
    <div role="status" aria-label="Alertas">
      <ul className="flex flex-wrap gap-2 empty:hidden">
        {maySeePaymentAlerts(employee) && <PaymentsToConfirm />}
        {maySeeProductionAlerts(employee) && <LateRounds />}
      </ul>
    </div>
  );
}

/** Red, to the payment screen of the oldest one's order; unlinked when none has an order (debt payments). */
function PaymentsToConfirm() {
  const alerts = useSettlementAlerts().data;
  if (!alerts || alerts.paymentsToConfirm === 0) return null;
  return (
    <Chip style={PAYMENT} to={alerts.oldestOrderId && `/orders/${alerts.oldestOrderId}/payment`}>
      <CircleAlert aria-hidden="true" className="size-4" />
      {paymentsToConfirmLabel(alerts.paymentsToConfirm)}
    </Chip>
  );
}

/** Amber, to the kitchen display. */
function LateRounds() {
  const alerts = useProductionAlerts().data;
  if (!alerts || alerts.delayedKitchenRounds === 0) return null;
  return (
    <Chip style={KITCHEN} to="/kds">
      <ChefHat aria-hidden="true" className="size-4" />
      {lateRoundsLabel(alerts.delayedKitchenRounds, alerts.kitchenDelayMinutes)}
    </Chip>
  );
}

function Chip({ style, to, children }: { style: string; to: string | undefined; children: ReactNode }) {
  const shape = `flex min-h-12 items-center gap-2 rounded-xl px-3 text-sm font-medium ${style}`;
  return (
    <li>
      {to ? (
        <Link
          to={to}
          className={`${shape} underline-offset-4 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background`}
        >
          {children}
        </Link>
      ) : (
        <p className={shape}>{children}</p>
      )}
    </li>
  );
}
