import type { ReactNode } from 'react';

/** A centred POS screen on the muted surface, under the app's name (DS: 16 px gutter). */
export function PosScreen({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-background p-4">
      <h1 className="text-xl font-semibold text-muted-foreground">restaurantPRO — Ponto de venda</h1>
      {children}
    </main>
  );
}
