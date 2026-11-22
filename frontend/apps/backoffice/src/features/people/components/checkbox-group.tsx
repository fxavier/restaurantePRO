import type { ReactNode } from 'react';
import type { Choice } from '../hooks/use-choices';

type CheckboxGroupProps = {
  legend: string;
  choices: Choice[];
  value: readonly string[];
  onChange: (value: string[]) => void;
  emptyMessage: string;
  /** More controls inside the fieldset, after the boxes. */
  children?: ReactNode;
};

/** A fieldset of checkboxes over a list of ids; the value keeps the order the boxes were ticked in. */
export function CheckboxGroup({ legend, choices, value, onChange, emptyMessage, children }: CheckboxGroupProps) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-2 text-sm font-medium">{legend}</legend>
      {choices.length === 0 && <p className="text-sm text-muted-foreground">{emptyMessage}</p>}
      <ul className="flex flex-wrap gap-2">
        {choices.map((choice) => (
          <li key={choice.value}>
            <label className="flex min-h-9 items-center gap-2 rounded-md border bg-muted px-3 text-sm">
              <input
                type="checkbox"
                className="size-4 accent-primary"
                checked={value.includes(choice.value)}
                onChange={(event) =>
                  onChange(event.target.checked ? [...value, choice.value] : value.filter((id) => id !== choice.value))
                }
              />
              <span>{choice.label}</span>
            </label>
          </li>
        ))}
      </ul>
      {children}
    </fieldset>
  );
}
