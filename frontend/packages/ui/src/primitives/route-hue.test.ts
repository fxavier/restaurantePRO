import { describe, expect, it } from 'vitest';
import { routeChipFill, routeDot } from './route-hue';

describe('routeChipFill', () => {
  it("gives a conventional code its route's hue, in either language, and any other the neutral surface", () => {
    expect(routeChipFill('COZINHA')).toBe(routeChipFill('KITCHEN'));
    expect(routeChipFill('KITCHEN')).toContain('border-route-kitchen');
    expect(routeChipFill('KITCHEN')).toContain('bg-route-kitchen/15');
    expect(routeChipFill('BAR')).toContain('bg-route-bar/15');
    expect(routeChipFill('PANTRY')).toBe(routeChipFill('COPA'));
    expect(routeChipFill('COPA')).toContain('border-route-copa');
    expect(routeChipFill('SOBREMESAS')).toContain('border-border bg-muted');
    expect(routeChipFill(null)).toBe(routeChipFill('SOBREMESAS'));
    // The text stays `foreground` on every chip: light text on the route hues is below AA.
    for (const code of ['COZINHA', 'BAR', 'COPA', 'SOBREMESAS']) expect(routeChipFill(code)).toContain('text-foreground');
  });
});

describe('routeDot', () => {
  it("is the route's hue alone, and the secondary text for any other code", () => {
    expect(routeDot('COZINHA')).toBe('bg-route-kitchen');
    expect(routeDot('PANTRY')).toBe('bg-route-copa');
    expect(routeDot('GRILL')).toBe('bg-muted-foreground');
  });
});
