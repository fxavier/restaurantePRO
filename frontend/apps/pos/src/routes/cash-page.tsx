import { CashScreen } from '../features/cash/components/cash-screen';
import { StatusBar } from './status-bar';

/** `/cash` (§4; the light reference's grey page and white cards, as on `/tables`): the cash session and the shift close. */
export function CashPage() {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4">
        <h2 className="text-2xl font-semibold">Caixa & Turno</h2>
        <CashScreen />
      </main>
    </div>
  );
}
