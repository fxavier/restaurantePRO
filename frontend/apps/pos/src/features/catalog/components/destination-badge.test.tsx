import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DestinationBadge } from './destination-badge';

describe('DestinationBadge', () => {
  it.each([
    ['KITCHEN', 'Cozinha', 'border-route-kitchen'],
    ['BAR', 'Bar', 'border-route-bar'],
    ['COPA', 'Copa', 'border-route-copa'],
    ['PANTRY', 'Copa', 'border-route-copa'],
  ])('names %s "%s" in its fixed colour', (code, label, colour) => {
    render(<DestinationBadge code={code} />);
    expect(screen.getByText(label)).toHaveClass(colour);
  });

  it("shows a tenant's own code as itself, on a neutral badge", () => {
    render(<DestinationBadge code="GRILL" />);
    expect(screen.getByText('GRILL')).toHaveAttribute('data-variant', 'secondary');
    expect(screen.getByText('GRILL').className).not.toMatch(/bg-route/);
  });

  it('shows nothing for an item that is not prepared', () => {
    const { container } = render(<DestinationBadge code={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
