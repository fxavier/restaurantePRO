import { Navigate } from 'react-router';
import { KitchenScreen } from '../features/kitchen/components/kitchen-screen';
import { mayWatchKitchen } from '../features/kitchen/kitchen';
import { useEmployee } from '../lib/auth/use-employee';
import { StatusBar } from './status-bar';

/** `/kds` (§4 "Ecrã de cozinha", Stitch "KDS"): a station's rounds, moved from queued to taken off the screen. */
export function KdsPage() {
  const employee = useEmployee();
  // The section bar hides the link without PRODUCTION_VIEW; a typed URL goes home. The backend enforces either way.
  if (!mayWatchKitchen(employee)) return <Navigate to="/tables" replace />;
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4 lg:p-6">
        <h2 className="text-2xl font-semibold">Ecrã de cozinha</h2>
        <KitchenScreen />
      </main>
    </div>
  );
}
