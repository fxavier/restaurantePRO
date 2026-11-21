import { CashScreen } from '../features/cash/components/cash-screen';
import { StatusBar } from './status-bar';

/** `/cash` (§4, Stitch "Caixa e Fecho de Turno"): the cash session and the shift close. */
export function CashPage() {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4 lg:p-6">
        <h2 className="text-2xl font-semibold">Caixa & Turno</h2>
        <CashScreen />
      </main>
    </div>
  );
}
