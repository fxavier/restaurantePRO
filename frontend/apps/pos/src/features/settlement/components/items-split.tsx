import { Button, LOCALE } from '@restaurantpro/ui';
import { Minus, Plus } from 'lucide-react';
import { useState } from 'react';
import { quantityText, type Settlement, UNIT } from '../settlement';

type Props = {
  settlement: Settlement;
  disabled: boolean;
  onSplit: (lines: { lineId: string; quantity: string }[]) => void;
};

const quantity = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 3 });

/** A split by items (§4 "por artigos/quantidades"): whole units of each line still in no part, as one new part. */
export function ItemsSplit({ settlement, disabled, onSplit }: Props) {
  // Thousandths chosen per line; a line the server no longer offers is simply not shown.
  const [chosen, setChosen] = useState<ReadonlyMap<string, number>>(new Map());
  const picked = settlement.undivided.flatMap(({ lineId, available }) => {
    const value = Math.min(chosen.get(lineId) ?? 0, available);
    return value > 0 ? [{ lineId, quantity: quantityText(value) }] : [];
  });

  const step = (lineId: string, by: number) => setChosen(new Map(chosen).set(lineId, (chosen.get(lineId) ?? 0) + by));

  if (settlement.undivided.length === 0) {
    return <p className="text-muted-foreground">Todos os artigos já estão divididos.</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <ul aria-label="Artigos por dividir" className="flex flex-col gap-2">
        {settlement.undivided.map(({ lineId, description, available }) => {
          const value = Math.min(chosen.get(lineId) ?? 0, available);
          return (
            <li key={lineId} className="flex items-center gap-3 rounded-lg bg-muted p-2">
              <span className="flex-1 px-2 font-medium">{description}</span>
              <span className="text-muted-foreground tabular-nums">
                {quantity.format(value / UNIT)} de {quantity.format(available / UNIT)}
              </span>
              <Button variant="outline" size="touch" aria-label={`Menos ${description}`} disabled={value < UNIT} onClick={() => step(lineId, -UNIT)}>
                <Minus aria-hidden="true" />
              </Button>
              <Button
                variant="outline"
                size="touch"
                aria-label={`Mais ${description}`}
                disabled={value + UNIT > available}
                onClick={() => step(lineId, UNIT)}
              >
                <Plus aria-hidden="true" />
              </Button>
            </li>
          );
        })}
      </ul>
      <Button
        size="touch"
        disabled={disabled || picked.length === 0}
        onClick={() => {
          onSplit(picked);
          setChosen(new Map());
        }}
      >
        Criar divisão
      </Button>
    </div>
  );
}
