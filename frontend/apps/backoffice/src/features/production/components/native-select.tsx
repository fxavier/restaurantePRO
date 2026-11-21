import type { ComponentProps } from 'react';

/**
 * A native select in the field style of `Input` (DESIGN.md: `bg-muted`, `border-input`, ring on focus): keyboard and
 * screen-reader behaviour for free, and Testing Library selects it by its label.
 */
export function NativeSelect({ className = '', ...props }: ComponentProps<'select'>) {
  return (
    <select
      {...props}
      className={`h-9 w-full rounded-md border border-input bg-muted px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50 aria-invalid:border-destructive ${className}`}
    />
  );
}
