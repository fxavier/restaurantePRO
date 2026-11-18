import { render, screen } from '@testing-library/react';
import { Circle, Lock } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { StatusBadge, type StatusMap } from './status-badge';

const tables: StatusMap<'FREE' | 'LOCKED'> = {
  FREE: { label: 'Livre', icon: Circle, tone: 'neutral' },
  LOCKED: { label: 'Bloqueada', icon: Lock, tone: 'destructive' },
};

describe('StatusBadge', () => {
  it('shows the status as a label, never by colour alone', () => {
    render(<StatusBadge status="LOCKED" statuses={tables} />);

    expect(screen.getByText('Bloqueada')).toBeVisible();
    expect(screen.queryByText('Livre')).not.toBeInTheDocument();
  });

  it('adds the status icon as decoration hidden from assistive technology', () => {
    render(<StatusBadge status="FREE" statuses={tables} />);

    const badge = screen.getByText('Livre').closest('[data-slot="badge"]');
    expect(badge?.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });
});
