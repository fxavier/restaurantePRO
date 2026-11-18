import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DataState } from './data-state';

describe('DataState', () => {
  it('shows a busy placeholder while loading, and not the content', () => {
    render(
      <DataState state="loading">
        <p>Mesa 1</p>
      </DataState>,
    );

    expect(screen.getByRole('status', { name: 'A carregar…' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('Mesa 1')).not.toBeInTheDocument();
  });

  it('shows the empty message', () => {
    render(
      <DataState state="empty" emptyMessage="Sem mesas nesta sala">
        <p>Mesa 1</p>
      </DataState>,
    );

    expect(screen.getByText('Sem mesas nesta sala')).toBeInTheDocument();
  });

  it('shows an error with a retry', async () => {
    const onRetry = vi.fn();
    render(
      <DataState state="error" onRetry={onRetry}>
        <p>Mesa 1</p>
      </DataState>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível carregar.');
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows the unavailable state with a retry', async () => {
    const onRetry = vi.fn();
    render(
      <DataState state="unavailable" onRetry={onRetry}>
        <p>Mesa 1</p>
      </DataState>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Sem ligação');
    await userEvent.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows the content when ready', () => {
    render(
      <DataState state="ready">
        <p>Mesa 1</p>
      </DataState>,
    );

    expect(screen.getByText('Mesa 1')).toBeInTheDocument();
  });
});
