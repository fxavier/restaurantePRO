import type { StatusMap } from '@restaurantpro/ui';
import { render, screen } from '@testing-library/react';
import { CalendarClock, UserX } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { StatusChip } from './state-tint';

const STATUSES: StatusMap<'BOOKED' | 'NO_SHOW'> = {
  BOOKED: { label: 'Por chegar', icon: CalendarClock, tone: 'success' },
  NO_SHOW: { label: 'Não compareceu', icon: UserX, tone: 'destructive' },
};

describe('StatusChip', () => {
  it('says the state in words, its icon hidden from assistive technology', () => {
    const { container } = render(<StatusChip status="NO_SHOW" statuses={STATUSES} />);

    expect(screen.getByText('Não compareceu')).toBeInTheDocument();
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });
});
