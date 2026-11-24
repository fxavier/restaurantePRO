import { render, screen } from '@testing-library/react';
import { Circle, CircleAlert, CircleCheck, Clock, Info, Lock } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { StatusBadge, type StatusMap, type StatusTone } from './status-badge';
import { StatusPill } from './status-pill';

const TONES: { tone: StatusTone; label: string; icon: typeof Circle }[] = [
  { tone: 'success', label: 'Livre', icon: CircleCheck },
  { tone: 'warning', label: 'Ocupada', icon: Clock },
  { tone: 'info', label: 'Conta pedida', icon: Info },
  { tone: 'destructive', label: 'Bloqueada', icon: Lock },
  { tone: 'neutral', label: 'A ligar…', icon: Circle },
  { tone: 'primary', label: 'Pago', icon: CircleAlert },
];

describe('StatusPill', () => {
  it.each(TONES)('says the $tone state in words, never by its tint alone', ({ tone, label, icon }) => {
    const { container } = render(
      <StatusPill tone={tone} icon={icon}>
        {label}
      </StatusPill>,
    );

    expect(screen.getByText(label)).toBeVisible();
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('passes a role and a name through, for a live chip', () => {
    render(
      <StatusPill tone="success" role="status" aria-label="Ligação">
        Ligado
      </StatusPill>,
    );

    expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Ligado');
  });
});

describe('StatusBadge look="tint"', () => {
  const tables: StatusMap<'FREE' | 'LOCKED'> = {
    FREE: { label: 'Livre', icon: CircleCheck, tone: 'success' },
    LOCKED: { label: 'Bloqueada', icon: Lock, tone: 'destructive' },
  };

  it('shows the status as a tinted pill with its label and decorative icon', () => {
    const { container } = render(<StatusBadge status="LOCKED" statuses={tables} look="tint" />);

    expect(screen.getByText('Bloqueada')).toBeVisible();
    expect(screen.queryByText('Livre')).not.toBeInTheDocument();
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });
});
