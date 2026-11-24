import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PermissionMatrix } from './permission-matrix';

const catalogue = { what: 'as permissões', areas: [], pending: false, error: null, retry: () => undefined };

describe('PermissionMatrix total', () => {
  it('counts the active permissions in green, and none in the neutral grey', () => {
    const { rerender } = render(<PermissionMatrix catalogue={catalogue} value={['CASH_VIEW']} onChange={() => undefined} />);
    expect(screen.getByText('1 ativas')).toHaveClass('text-success-text');

    rerender(<PermissionMatrix catalogue={catalogue} value={[]} onChange={() => undefined} />);
    expect(screen.getByText('0 ativas')).toHaveClass('text-muted-foreground');
  });
});
