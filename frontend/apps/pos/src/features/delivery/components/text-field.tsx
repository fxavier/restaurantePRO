import { cn, Input, Label } from '@restaurantpro/ui';
import { type ComponentProps, useId } from 'react';

type Props = Omit<ComponentProps<typeof Input>, 'id' | 'value' | 'onChange'> & {
  label: string;
  value: string;
  onChange: (value: string) => void;
};

/** A labelled, touch-sized text input (POS forms are plain controlled inputs, FC). */
export function TextField({ label, value, onChange, className, ...input }: Props) {
  const id = useId();
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        autoComplete="off"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="h-12 text-base md:text-base"
        {...input}
      />
    </div>
  );
}
