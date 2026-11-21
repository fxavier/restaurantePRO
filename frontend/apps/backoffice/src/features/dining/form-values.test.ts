import { describe, expect, it } from 'vitest';
import { settingsFormValues, tableFormValues } from './form-values';

describe('tableFormValues', () => {
  it("is the table's code, seats as text, area and state", () => {
    expect(tableFormValues({ code: 'M01', seats: 4, areaId: 'a1', active: false })).toEqual({
      code: 'M01',
      seats: '4',
      areaId: 'a1',
      active: false,
    });
  });

  it('leaves blank what the server did not send, and a table is active by default', () => {
    expect(tableFormValues({})).toEqual({ code: '', seats: '', areaId: '', active: true });
  });
});

describe('settingsFormValues', () => {
  it('is both minute counts as text, zero included', () => {
    expect(settingsFormValues({ reservationWindowMinutes: 120, noShowGraceMinutes: 0 })).toEqual({
      reservationWindowMinutes: '120',
      noShowGraceMinutes: '0',
    });
  });

  it('leaves blank what the server did not send', () => {
    expect(settingsFormValues({})).toEqual({ reservationWindowMinutes: '', noShowGraceMinutes: '' });
  });
});
