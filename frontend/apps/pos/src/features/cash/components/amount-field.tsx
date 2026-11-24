import { Input, Label } from '@restaurantpro/ui';
import { useId } from 'react';

type Props = {
  label: string;
  value: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
  /** The currency the amount is in, when the server has named it (the session's). */
  currency?: string;
};

/** A typed amount, kept as text: digits and one decimal separator, never parsed into a float. */
export function AmountField({ label, value, onChange, readOnly, currency }: Props) {
  const id = useId();
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id} className="text-base">
        {label}
        {currency && <span className="font-normal text-muted-foreground"> ({currency})</span>}
      </Label>
      <Input
        id={id}
        inputMode="decimal"
        autoComplete="off"
        value={value}
        readOnly={readOnly}
        onChange={(event) => onChange(event.target.value.replace(/[^\d.,]/g, ''))}
        className="h-14 text-right text-2xl font-semibold tabular-nums md:text-2xl"
      />
    </div>
  );
}
