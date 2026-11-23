import { cn } from '@restaurantpro/ui';
import type { LucideIcon } from 'lucide-react';
import { type ReactNode, useId } from 'react';

export type Choice<V extends string> = { value: V; label: string; hint?: string; icon?: LucideIcon };

type Props<V extends string> = {
  legend: ReactNode;
  /** The radios' shared `name`, unique in the form. */
  name: string;
  choices: readonly Choice<V>[];
  value: V | undefined;
  onChange: (value: V) => void;
  disabled?: boolean;
  className?: string;
};

/**
 * One choice among a few, as touch cards (Stitch's channel and zone tiles): native radios, so the arrow keys, the
 * screen reader and the form behave as the platform does. The chosen card is filled and checked, never by colour only.
 */
export function ChoiceGroup<V extends string>({ legend, name, choices, value, onChange, disabled, className }: Props<V>) {
  const idPrefix = useId();
  return (
    <fieldset className="flex min-w-0 flex-col gap-2" disabled={disabled}>
      <legend className="mb-2 flex items-center gap-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        {legend}
      </legend>
      <div className={cn('grid gap-2', className)}>
        {choices.map(({ value: choice, label, hint, icon: Icon }) => {
          // The radio is named by its label alone and described by its hint: the card's text would glue the two.
          const labelId = `${idPrefix}-${choice}-label`;
          const hintId = `${idPrefix}-${choice}-hint`;
          return (
            <label
              key={choice}
              className="relative flex min-h-14 items-center gap-3 rounded-lg border bg-muted px-3 py-2 has-checked:border-primary has-checked:bg-primary/15 has-focus-visible:ring-2 has-focus-visible:ring-ring has-disabled:opacity-50"
            >
              {/* The radio itself covers the card, transparent: a tap anywhere on it is a tap on the radio. */}
              <input
                type="radio"
                name={name}
                value={choice}
                checked={value === choice}
                onChange={() => onChange(choice)}
                aria-labelledby={labelId}
                aria-describedby={hint ? hintId : undefined}
                className="peer absolute inset-0 cursor-pointer appearance-none rounded-lg opacity-0 disabled:cursor-not-allowed"
              />
              {Icon && <Icon aria-hidden="true" className="size-6 shrink-0 text-primary-hover" />}
              <span className="flex min-w-0 flex-1 flex-col">
                <span id={labelId} className="font-semibold">
                  {label}
                </span>
                {hint && (
                  <span id={hintId} className="text-sm text-muted-foreground">
                    {hint}
                  </span>
                )}
              </span>
              <span
                aria-hidden="true"
                className="size-5 shrink-0 rounded-full border-2 border-input peer-checked:border-primary peer-checked:bg-primary"
              />
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
