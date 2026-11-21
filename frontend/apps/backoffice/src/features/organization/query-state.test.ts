import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { queryState } from './query-state';

const failure = (status: number) => new ApiError({ status, code: `HTTP_${status}`, title: 'x' });

describe('queryState', () => {
  it('is loading while nothing has arrived', () => {
    expect(queryState(null, undefined)).toBe('loading');
  });

  it('is empty for an empty list and ready for a list with items or a single object', () => {
    expect(queryState(null, [])).toBe('empty');
    expect(queryState(null, [1])).toBe('ready');
    expect(queryState(null, { version: 0 })).toBe('ready');
  });

  it('is unavailable with no answer or a 502/503, and error otherwise, even with data read before', () => {
    expect(queryState(failure(0), undefined)).toBe('unavailable');
    expect(queryState(failure(503), [1])).toBe('unavailable');
    expect(queryState(failure(500), [1])).toBe('error');
    expect(queryState(new Error('boom'), undefined)).toBe('error');
  });
});
