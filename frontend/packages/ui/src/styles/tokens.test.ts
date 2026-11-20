// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const tokensCss = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');

/** A block's variables, as written in tokens.css: `:root` (light) or `.dark` (Stitch). */
function block(selector: string): Record<string, string> {
  const body = new RegExp(`${selector}\\s*{([^}]*)}`).exec(tokensCss)?.[1] ?? '';
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]!.trim()]));
}

const tokens = () => block(':root');

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

  describe('dark (Stitch)', () => {
    const dark = () => ({ ...tokens(), ...block('\\.dark') });

    it('match DESIGN.md', () => {
      expect(dark()).toMatchObject({
        background: '#0B0F19',
        foreground: '#DAE2FD',
        card: '#151D2E',
        muted: '#1E293B',
        'muted-foreground': '#BBCABF',
        border: '#334155',
        input: '#86948A',
        primary: '#10B981',
        'primary-hover': '#4EDEA3',
        'primary-foreground': '#0B0F19',
        ring: '#4EDEA3',
        success: '#10B981',
        warning: '#F59E0B',
        destructive: '#EF4444',
        info: '#0284C7',
        error: '#FFB4AB',
        'route-kitchen': '#F97316',
        'route-bar': '#8B5CF6',
        'route-copa': '#06B6D4',
      });
    });

    it.each([
      ['foreground', 'background'],
      ['foreground', 'card'],
      ['muted-foreground', 'card'],
      ['muted-foreground', 'muted'],
      ['primary', 'card'],
      ['primary-hover', 'muted'],
      ['error', 'card'],
      ['error', 'muted'],
      ['primary-foreground', 'primary'],
      ['primary-foreground', 'primary-hover'],
      ['success-foreground', 'success'],
      ['warning-foreground', 'warning'],
      ['destructive-foreground', 'destructive'],
      ['info-foreground', 'info'],
      ['background', 'route-kitchen'],
      ['background', 'route-bar'],
      ['background', 'route-copa'],
    ])('give text %s on %s a contrast of at least 4.5:1', (text, surface) => {
      const t = dark();
      expect(contrast(t[text]!, t[surface]!)).toBeGreaterThanOrEqual(4.5);
    });

    it.each([
      ['input', 'card'],
      ['ring', 'background'],
      ['ring', 'card'],
      ['destructive', 'card'],
    ])('give the boundary %s on %s a contrast of at least 3:1', (boundary, surface) => {
      const t = dark();
      expect(contrast(t[boundary]!, t[surface]!)).toBeGreaterThanOrEqual(3);
    });
  });
});
