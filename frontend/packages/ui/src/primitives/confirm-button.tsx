import type { ComponentProps } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '../components/button';

type ConfirmButtonProps = ComponentProps<typeof Button> & {
  /** The command's request is in flight: one deliberate tap, no second one until the server answers. */
  pending?: boolean;
};

/** The view's primary action (DS principle 4): disabled with a spinner while in flight; the label stays, so nothing shifts. */
export function ConfirmButton({ pending = false, disabled, children, ...props }: ConfirmButtonProps) {
  return (
    <Button {...props} disabled={disabled || pending} aria-busy={pending}>
      {pending && <Loader2 aria-hidden="true" className="animate-spin" />}
      {children}
    </Button>
  );
}
