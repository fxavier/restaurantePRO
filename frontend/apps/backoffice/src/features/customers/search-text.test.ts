import { describe, expect, it } from 'vitest';
import { searchText } from './search-text';

describe('searchText', () => {
  it.each([
    ['Lus', 'Lus'],
    ['  Lus  ', 'Lus'],
    ['Lu', undefined],
    ['Çé', undefined],
    ['Ção', 'Ção'],
    ['', undefined],
    ['   ', undefined],
    ['4567', '4567'],
    ['456', undefined],
    ['+258 84', '+258 84'],
    ['(84) 1-2', '(84) 1-2'],
    ['84.1', undefined],
    ['12a', '12a'],
    ['1a', undefined],
  ])('%j → %j', (raw, expected) => {
    expect(searchText(raw)).toBe(expected);
  });
});
