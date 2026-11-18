import { StatusBar } from './status-bar';

/** `/tables`, the POS home: stands in until the table map (issue 10). */
export function TablesPage() {
  return (
    <div className="flex min-h-screen flex-col bg-muted">
      <StatusBar />
      <main className="flex flex-1 flex-col items-center justify-center gap-2 p-4">
        <h2 className="text-2xl font-semibold">Mesas</h2>
        <p className="text-muted-foreground">O mapa de mesas chega no próximo passo.</p>
      </main>
    </div>
  );
}
