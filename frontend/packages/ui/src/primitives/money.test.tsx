import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { formatMoney, Money } from './money';

const NBSP = ' ';

describe('formatMoney', () => {
  it('formats an amount in pt-MZ with the currency it is given', () => {
    expect(formatMoney({ amount: '1234.5', currency: 'MZN' })).toBe(`1234,50${NBSP}MTn`);
    expect(formatMoney({ amount: '-3.1', currency: 'EUR' })).toBe(`-3,10${NBSP}€`);
  });

  it('accepts a JSON number as well as a string', () => {
    expect(formatMoney({ amount: 1234.5, currency: 'MZN' })).toBe(`1234,50${NBSP}MTn`);
  });

  it('formats a decimal string exactly, without passing through a float', () => {
    expect(formatMoney({ amount: '12345678901234567.89', currency: 'MZN' })).toBe(
      ['12', '345', '678', '901', '234', '567,89'].join(NBSP) + `${NBSP}MTn`,
    );
  });
});

describe('Money', () => {
  it('shows the formatted amount with tabular numerals', () => {
    render(<Money value={{ amount: '250.00', currency: 'MZN' }} />);

    const amount = screen.getByText('250,00 MTn');
    expect(amount.textContent).toBe(`250,00${NBSP}MTn`);
    expect(amount).toHaveClass('tabular-nums');
  });
});
