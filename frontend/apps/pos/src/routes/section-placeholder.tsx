import { StatusBar } from './status-bar';

/** A section whose screen is not built yet: the status bar and the section bar, its name, and that it is coming. */
export function SectionPlaceholder({ title }: { title: string }) {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4 lg:p-6">
        <h2 className="text-2xl font-semibold">{title}</h2>
        <p className="text-muted-foreground">Em preparação.</p>
      </main>
    </div>
  );
}
