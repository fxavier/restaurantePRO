import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmButton } from './confirm-button';

describe('ConfirmButton', () => {
  it('confirms on one tap', async () => {
    const onClick = vi.fn();
    render(<ConfirmButton onClick={onClick}>Pedir</ConfirmButton>);

    await userEvent.click(screen.getByRole('button', { name: 'Pedir' }));

    expect(onClick).toHaveBeenCalledOnce();
  });

  it('is disabled and busy, keeping its label, while the request is in flight', async () => {
    const onClick = vi.fn();
    render(
      <ConfirmButton pending onClick={onClick}>
        Pedir
      </ConfirmButton>,
    );

    const button = screen.getByRole('button', { name: 'Pedir' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('can be disabled while the service is unavailable', () => {
    render(<ConfirmButton disabled>Pedir</ConfirmButton>);

    expect(screen.getByRole('button', { name: 'Pedir' })).toBeDisabled();
  });
});
