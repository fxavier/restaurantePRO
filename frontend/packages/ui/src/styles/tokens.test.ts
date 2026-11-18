// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const tokensCss = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');

/** The light theme's `:root` variables, as written in tokens.css. */
function tokens(): Record<string, string> {
  const root = /:root\s*{([^}]*)}/.exec(tokensCss)?.[1] ?? '';
  return Object.fromEntries([...root.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]!.trim()]));
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const channel = parseInt(hex.slice(i, i + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.x contrast ratio. */
function contrast(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (light + 0.05) / (dark + 0.05);
}

describe('design tokens', () => {
  it('match the design system table', () => {
    expect(tokens()).toMatchObject({
      background: '#FFFFFF',
      foreground: '#0F172A',
      muted: '#F1F5F9',
      'muted-foreground': '#475569',
      border: '#E2E8F0',
      input: '#64748B',
      primary: '#166FE5',
      'primary-hover': '#1259C3',
      'primary-foreground': '#FFFFFF',
      brand: '#1877F2',
      ring: '#1877F2',
      success: '#15803D',
      'success-foreground': '#FFFFFF',
      warning: '#FEF3C7',
      'warning-foreground': '#92400E',
      destructive: '#B91C1C',
      'destructive-foreground': '#FFFFFF',
      info: 'var(--primary)',
      radius: '0.5rem',
    });
  });

  it.each([
    ['primary-foreground', 'primary'],
    ['muted-foreground', 'background'],
    ['muted-foreground', 'muted'],
    ['foreground', 'background'],
    ['primary-hover', 'muted'],
    ['success-foreground', 'success'],
    ['warning-foreground', 'warning'],
    ['destructive-foreground', 'destructive'],
  ])('give text %s on %s a contrast of at least 4.5:1', (text, surface) => {
    const t = tokens();
    expect(contrast(t[text]!, t[surface]!)).toBeGreaterThanOrEqual(4.5);
  });

  it.each([
    ['input', 'background'],
    ['ring', 'background'],
  ])('give the boundary %s on %s a contrast of at least 3:1', (boundary, surface) => {
    const t = tokens();
    expect(contrast(t[boundary]!, t[surface]!)).toBeGreaterThanOrEqual(3);
  });
});
