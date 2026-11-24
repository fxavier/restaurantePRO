import { NativeSelect } from '@restaurantpro/ui';
import { useId } from 'react';

type StatusFilterProps<Value extends string> = {
  label: string;
  value: Value;
  options: readonly { value: Value; label: string }[];
  onChange: (value: Value) => void;
};

/** A list's state filter, beside its title: a labelled native select over the states the list may ask the server for. */
export function StatusFilter<Value extends string>({ label, value, options, onChange }: StatusFilterProps<Value>) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        {label}
      </label>
      <NativeSelect id={id} className="h-10 w-auto" value={value} onChange={(event) => onChange(event.target.value as Value)}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </NativeSelect>
    </div>
  );
}
