import { describe, expect, it } from 'vitest';
import { reviewDatePreset, reviewDateQuery } from './review-dates';
describe('review time selection', () => {
  it('includes the last selected second without crossing into the following second', () => {
    expect(reviewDateQuery({ start: '2026-09-01T00:00:00', end: '2026-09-30T23:59:59' })).toEqual({
      from: new Date('2026-09-01T00:00:00').toISOString(), to: new Date('2026-10-01T00:00:00').toISOString(),
    });
  });
  it('supports unbounded history and rejects inverted or malformed intervals', () => {
    expect(reviewDateQuery({ start: '', end: '' })).toEqual({ from: undefined, to: undefined });
    expect(() => reviewDateQuery({ start: '2026-10-26T10:00:00', end: '2026-10-25T10:00:00' })).toThrow('开始时间');
    expect(() => reviewDateQuery({ start: 'bad', end: '' })).toThrow('有效');
  });
  it('uses local calendar days for today and yesterday', () => {
    const now = new Date('2026-10-26T22:00:00');
    expect(reviewDatePreset('today', now)).toEqual({ start: '2026-10-26T00:00:00', end: '2026-10-26T23:59:59' });
    expect(reviewDatePreset('yesterday', now)).toEqual({ start: '2026-10-25T00:00:00', end: '2026-10-25T23:59:59' });
  });
});
