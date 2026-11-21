import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useStoreToday } from './hooks/use-store-today';

describe("the store's today", () => {
  afterEach(() => void vi.useRealTimers());

  it("turns over at the store's midnight, on a screen left open", () => {
    vi.useFakeTimers();
    // 23:59:30 in Maputo (UTC+2): still the 20th there.
    vi.setSystemTime(new Date('2026-11-20T21:59:30Z'));
    const { result } = renderHook(() => useStoreToday('Africa/Maputo'));
    expect(result.current).toBe('2026-11-20');

    act(() => void vi.advanceTimersByTime(60_000));

    expect(result.current).toBe('2026-11-21');
  });
});
