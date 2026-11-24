import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

type FullPageProps = { icon: LucideIcon; title: string; children?: ReactNode };

/** A whole-page state before or instead of the shell: login redirect, company blocked, no connection (story 11). */
export function FullPage({ icon: Icon, title, children }: FullPageProps) {
  return (
    <main className="grid min-h-screen place-items-center bg-background p-6">
      <div className="flex w-full max-w-md flex-col items-center gap-4 rounded-2xl border bg-card p-8 text-center">
        <Icon aria-hidden="true" className="size-10 text-muted-foreground" />
        <h1 className="text-2xl font-semibold">{title}</h1>
        {children}
      </div>
    </main>
  );
}
