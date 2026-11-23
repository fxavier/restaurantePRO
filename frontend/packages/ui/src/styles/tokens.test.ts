// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const tokensCss = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');

/** A block's variables, as written in tokens.css: `:root` (light) or `.dark` (Stitch). */
function block(selector: string): Record<string, string> {
  const body = new RegExp(`${selector}\\s*{([^}]*)}`).exec(tokensCss)?.[1] ?? '';
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]!.trim()]));
}

/** `:root`, each `var(--x)` replaced by the value of `x`, so the contrast checks read colours. */
const tokens = () => {
  const raw = block(':root');
  const resolve = (value: string): string => {
    const name = /^var\(--([\w-]+)\)$/.exec(value)?.[1];
    return name && raw[name] ? resolve(raw[name]) : value;
  };
  return Object.fromEntries(Object.entries(raw).map(([name, value]) => [name, resolve(value)]));
};

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
  it('match the light reference (DESIGN.md "Light reference")', () => {
    expect(tokens()).toMatchObject({
      background: '#F0F0ED',
      foreground: '#1C1C1A',
      card: '#FFFFFF',
      popover: '#FFFFFF',
      muted: '#EAEAE6',
      'muted-foreground': '#575752',
      border: '#D6D6D1',
      input: '#7A7A74',
      primary: '#0B7A0B',
      'primary-hover': '#086208',
      'primary-foreground': '#FFFFFF',
      brand: '#0B7A0B',
      ring: '#1C64C4',
      success: '#0B7A0B',
      'success-foreground': '#FFFFFF',
      warning: '#9A5700',
      'warning-foreground': '#FFFFFF',
      info: '#1D5FB0',
      'info-foreground': '#FFFFFF',
      destructive: '#B3261E',
      'destructive-foreground': '#FFFFFF',
      error: '#B3261E',
      'success-tint': '#D4EDD2',
      'success-border': '#6CC46C',
      'success-text': '#145A14',
      'warning-tint': '#F9DEA9',
      'warning-border': '#E9A21B',
      'warning-text': '#6B3E00',
      'info-tint': '#D3E3F8',
      'info-border': '#82AFEB',
      'info-text': '#1A4A8A',
      'destructive-tint': '#F8D6D6',
      'destructive-border': '#E58C8C',
      'destructive-text': '#8E1B1B',
      // shared with `.dark`, which sets neither: the BackOffice keeps them
      radius: '0.5rem',
      'route-kitchen': '#F97316',
    });
  });

  it.each([
    ['foreground', 'background'],
    ['foreground', 'card'],
    ['muted-foreground', 'card'],
    ['muted-foreground', 'background'],
    ['muted-foreground', 'muted'],
    ['primary-foreground', 'primary'],
    ['primary-foreground', 'primary-hover'],
    ['primary', 'card'],
    ['primary', 'background'],
    ['primary-hover', 'muted'],
    ['error', 'card'],
    ['error', 'background'],
    ['warning', 'card'],
    ['info', 'card'],
    ['success-foreground', 'success'],
    ['warning-foreground', 'warning'],
    ['info-foreground', 'info'],
    ['destructive-foreground', 'destructive'],
    // the table states: their text, and the table code and details, on their tint
    ['success-text', 'success-tint'],
    ['warning-text', 'warning-tint'],
    ['info-text', 'info-tint'],
    ['destructive-text', 'destructive-tint'],
    ['foreground', 'success-tint'],
    ['foreground', 'warning-tint'],
    ['foreground', 'info-tint'],
    ['foreground', 'destructive-tint'],
    ['muted-foreground', 'success-tint'],
    ['muted-foreground', 'warning-tint'],
    ['muted-foreground', 'info-tint'],
    ['muted-foreground', 'destructive-tint'],
  ])('give text %s on %s a contrast of at least 4.5:1', (text, surface) => {
    const t = tokens();
    expect(contrast(t[text]!, t[surface]!)).toBeGreaterThanOrEqual(4.5);
  });

  it.each([
    ['input', 'card'],
    ['input', 'background'],
    ['ring', 'card'],
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
