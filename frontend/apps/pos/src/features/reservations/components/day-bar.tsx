import { Button } from '@restaurantpro/ui';
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { addDays, dayTitle } from '../store-time';

type Props = {
  /** The day shown, `YYYY-MM-DD`. */
  date: string;
  /** The store's today. */
  today: string;
  onDate: (date: string) => void;
};

/** Stitch's date header: the day, the previous and next ones, and the two days a host books most. */
export function DayBar({ date, today, onDate }: Props) {
  const tomorrow = addDays(today, 1);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button variant="ghost" size="touch" aria-label="Dia anterior" onClick={() => onDate(addDays(date, -1))}>
        <ChevronLeft aria-hidden="true" />
      </Button>
      <h3 className="flex min-w-0 items-center gap-2 text-2xl font-semibold">
        <CalendarDays aria-hidden="true" className="size-6 shrink-0 text-primary" />
        {dayTitle(date)}
      </h3>
      <Button variant="ghost" size="touch" aria-label="Dia seguinte" onClick={() => onDate(addDays(date, 1))}>
        <ChevronRight aria-hidden="true" />
      </Button>
      <div className="flex gap-2">
        {[
          { label: 'Hoje', day: today },
          { label: 'Amanhã', day: tomorrow },
        ].map(({ label, day }) => (
          <Button
            key={label}
            variant={date === day ? 'default' : 'outline'}
            size="touch"
            aria-pressed={date === day}
            onClick={() => onDate(day)}
          >
            {label}
          </Button>
        ))}
      </div>
    </div>
  );
}
