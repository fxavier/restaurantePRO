import { Construction } from 'lucide-react';

type Props = { eyebrow: string; title: string; description: string };

/** An area whose screen is not built yet: Stitch's page header (as on the terminals page), then that it is coming. */
export function AreaPlaceholder({ eyebrow, title, description }: Props) {
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        {eyebrow}
      </p>
      <h1 className="mt-2 text-2xl font-semibold">{title}</h1>
      <p className="mt-1 text-muted-foreground">{description}</p>
      <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
        <Construction aria-hidden="true" className="size-8 text-muted-foreground" />
        <p>Em preparação.</p>
      </div>
    </>
  );
}
