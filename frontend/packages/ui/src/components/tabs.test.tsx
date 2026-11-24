import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs';

function PillTabs() {
  return (
    <Tabs defaultValue="a">
      <TabsList variant="pill" aria-label="Áreas">
        <TabsTrigger value="a">Artigos</TabsTrigger>
        <TabsTrigger value="b">Famílias</TabsTrigger>
      </TabsList>
      <TabsContent value="a">Lista de artigos</TabsContent>
      <TabsContent value="b">Lista de famílias</TabsContent>
    </Tabs>
  );
}

describe('Tabs, pill variant', () => {
  it('keeps the tabs behaviour: one selected, another chosen by a tap', async () => {
    render(<PillTabs />);
    expect(screen.getByRole('tablist', { name: 'Áreas' })).toHaveAttribute('data-variant', 'pill');
    expect(screen.getByRole('tab', { name: 'Artigos' })).toHaveAttribute('aria-selected', 'true');

    await userEvent.click(screen.getByRole('tab', { name: 'Famílias' }));

    expect(screen.getByRole('tab', { name: 'Famílias' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel', { name: 'Famílias' })).toHaveTextContent('Lista de famílias');
  });

  it('is a wrapping row of touch-sized pills, the selected one solid green with no shadow', () => {
    render(<PillTabs />);
    expect(screen.getByRole('tablist')).toHaveClass('flex-wrap');
    const trigger = screen.getByRole('tab', { name: 'Artigos' }).className;
    expect(trigger).toContain('group-data-[variant=pill]/tabs-list:h-12');
    expect(trigger).toContain('group-data-[variant=pill]/tabs-list:data-[state=active]:text-primary-foreground');
    // The shared shadow is the default variant's only: a pill list never matches it.
    expect(trigger).toContain('group-data-[variant=default]/tabs-list:data-[state=active]:shadow-sm');
    expect(trigger).not.toMatch(/group-data-\[variant=pill\][^ ]*shadow-sm/);
  });
});
