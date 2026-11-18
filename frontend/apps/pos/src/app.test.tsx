import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from './routes';

describe('POS', () => {
  it('boots to the placeholder screen in Portuguese', () => {
    render(<RouterProvider router={createMemoryRouter(routes)} />);

    expect(screen.getByRole('heading', { level: 1, name: 'restaurantPRO — Ponto de venda' })).toBeInTheDocument();
    expect(screen.getByText('Este terminal ainda não está registado.')).toBeInTheDocument();
  });
});
