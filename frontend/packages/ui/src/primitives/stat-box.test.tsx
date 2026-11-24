import { render, screen, within } from '@testing-library/react';
import { Banknote } from 'lucide-react';
import { describe, expect, it } from 'vitest';
import { StatBox } from './stat-box';

describe('StatBox', () => {
  it('is a group named by its caption, holding the value and its context line', () => {
    render(<StatBox tone="success" caption="Recebido" icon={Banknote} value="1 831,30 MTn" meta="68 documentos" />);
    const box = screen.getByRole('group', { name: 'Recebido' });
    expect(within(box).getByText('1 831,30 MTn')).toBeInTheDocument();
    expect(within(box).getByText('68 documentos')).toBeInTheDocument();
  });

  it("takes a state's tint with its AA text, or the muted surface", () => {
    const { rerender } = render(<StatBox tone="warning" caption="Abaixo do mínimo" icon={Banknote} value="1 artigo" />);
    expect(screen.getByRole('group')).toHaveClass('bg-warning-tint', 'text-warning-text');
    rerender(<StatBox tone="muted" caption="Abaixo do mínimo" icon={Banknote} value="0 artigos" />);
    expect(screen.getByRole('group')).toHaveClass('bg-muted');
    expect(screen.getByRole('group')).not.toHaveClass('text-muted-foreground');
  });

  it('names its caption as a heading of the level asked, and as plain text otherwise', () => {
    const { rerender } = render(<StatBox tone="muted" caption="Impostos" icon={Banknote} value="0,00 MTn" captionAs="h3" />);
    expect(screen.getByRole('heading', { level: 3, name: 'Impostos' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Impostos' })).toBeInTheDocument();
    rerender(<StatBox tone="muted" caption="Impostos" icon={Banknote} value="0,00 MTn" />);
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });
});
