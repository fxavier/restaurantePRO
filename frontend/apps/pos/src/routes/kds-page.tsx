import { Navigate } from 'react-router';
import { useProductionAlerts } from '../features/alerts/hooks/use-alerts';
import { KitchenScreen } from '../features/kitchen/components/kitchen-screen';
import { mayWatchKitchen } from '../features/kitchen/kitchen';
import { useEmployee } from '../lib/auth/use-employee';
import { StatusBar } from './status-bar';

/**
 * `/kds` (§4 "Ecrã de cozinha", Stitch "KDS"; light reference): a station's rounds, moved from queued to taken off the
 * screen. A round not ready after production's kitchen delay is shown late: the same read, and so the same threshold,
 * as the status bar's and the footer's alerts (no request of its own; none, so nothing late, until it answers).
 */
export function KdsPage() {
  const employee = useEmployee();
  const alerts = useProductionAlerts();
  // The section bar hides the link without PRODUCTION_VIEW; a typed URL goes home. The backend enforces either way.
  if (!mayWatchKitchen(employee)) return <Navigate to="/tables" replace />;
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4">
        <KitchenScreen lateAfterMinutes={alerts.data?.kitchenDelayMinutes}>
          <h2 className="px-1 text-xl font-semibold">Ecrã de cozinha</h2>
        </KitchenScreen>
      </main>
    </div>
  );
}
