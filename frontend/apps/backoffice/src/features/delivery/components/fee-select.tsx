import type { ComponentProps } from 'react';

/** The native select of the zone form, in the field tokens of `Input` (a picker of a few options, no search). */
export function NativeSelect(props: ComponentProps<'select'>) {
  return (
    <select
      {...props}
      className="h-9 w-full rounded-md border border-input bg-muted px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50 aria-invalid:border-destructive"
    />
  );
}
