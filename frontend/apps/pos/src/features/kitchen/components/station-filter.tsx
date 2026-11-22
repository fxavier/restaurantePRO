import { Badge, Button } from '@restaurantpro/ui';
import type { ReactNode } from 'react';
import { DestinationBadge } from '../../catalog/components/destination-badge';

type Props = {
  stations: { code: string; count: number }[];
  /** Every station's active rounds. */
  total: number;
  /** The chosen station, none for all. */
  chosen?: string;
  onChoose: (station: string | undefined) => void;
};

/** Stitch's station pills: "Todas" and each station with its active rounds; the chosen one is pressed. */
export function StationFilter({ stations, total, chosen, onChoose }: Props) {
  return (
    <div role="group" aria-label="Estação" className="flex flex-wrap gap-2">
      <Pill pressed={chosen === undefined} count={total} onPress={() => onChoose(undefined)}>
        Todas
      </Pill>
      {stations.map(({ code, count }) => (
        <Pill key={code} pressed={chosen === code} count={count} onPress={() => onChoose(code)}>
          <DestinationBadge code={code} />
        </Pill>
      ))}
    </div>
  );
}

function Pill({ pressed, count, onPress, children }: { pressed: boolean; count: number; onPress: () => void; children: ReactNode }) {
  return (
    <Button variant={pressed ? 'default' : 'outline'} size="touch" aria-pressed={pressed} onClick={onPress}>
      {children}{' '}
      <Badge variant="secondary" className="tabular-nums">
        {count}
      </Badge>
    </Button>
  );
}
