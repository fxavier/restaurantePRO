import { Button, Input } from '@restaurantpro/ui';
import { Search } from 'lucide-react';
import type { DaySummary, StatusFilter } from '../reservation';

const PILLS: { status: StatusFilter; label: string }[] = [
  { status: 'ALL', label: 'Todas' },
  { status: 'BOOKED', label: 'Por chegar' },
  { status: 'SEATED', label: 'Sentadas' },
  { status: 'NO_SHOW', label: 'Não compareceram' },
  { status: 'CANCELLED', label: 'Canceladas' },
];

type Props = {
  summary: DaySummary;
  status: StatusFilter;
  onStatus: (status: StatusFilter) => void;
  query: string;
  onQuery: (query: string) => void;
};

/** Stitch's search box and state pills over the list; each pill counts its state. Local input only. */
export function ListFilter({ summary, status, onStatus, query, onQuery }: Props) {
  return (
    <div className="flex flex-col gap-3 rounded-xl border bg-card p-3">
      <div className="relative">
        <Search aria-hidden="true" className="absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          aria-label="Procurar por nome ou telefone"
          placeholder="Procurar por nome ou telefone"
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          className="h-12 pl-10 text-base md:text-base"
        />
      </div>
      <div className="flex flex-wrap gap-2">
        {PILLS.map((pill) => (
          <Button
            key={pill.status}
            variant={pill.status === status ? 'default' : 'outline'}
            size="touch"
            aria-pressed={pill.status === status}
            onClick={() => onStatus(pill.status)}
          >
            {pill.label} ({summary[pill.status].count})
          </Button>
        ))}
      </div>
    </div>
  );
}
