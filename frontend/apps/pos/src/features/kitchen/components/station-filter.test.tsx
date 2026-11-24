import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StationFilter } from './station-filter';

describe('StationFilter', () => {
  it('names two stations with the same label by their codes too, so each can be told apart', () => {
    render(
      <StationFilter
        stations={[
          { code: 'KITCHEN', count: 3 },
          { code: 'COZINHA', count: 1 },
          { code: 'BAR', count: 2 },
        ]}
        total={6}
        onChoose={() => {}}
      />,
    );

    expect(screen.getByRole('button', { name: 'Cozinha KITCHEN 3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cozinha COZINHA 1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Bar 2' })).toBeInTheDocument();
  });
});
