import { Store } from 'lucide-react';

/** A tab whose data belongs to one store, before a store is chosen in the top bar. */
export function StoreRequired({ what }: { what: string }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
      <Store aria-hidden="true" className="size-8 text-muted-foreground" />
      <p>Escolha uma loja na barra superior para ver {what}.</p>
    </div>
  );
}
