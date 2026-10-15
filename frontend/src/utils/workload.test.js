import { describe, expect, it } from 'vitest';
import {
  addDays, niceMax, progressSeries, rangeDays,
} from './workload.js';

describe('workload helpers', () => {
  it('lists every day of a range, ending today', () => {
    expect(rangeDays(3, '2026-10-15')).toEqual(['2026-10-13', '2026-10-14', '2026-10-15']);
    expect(rangeDays(0, '2026-10-15', '2026-10-14')).toEqual(['2026-10-14', '2026-10-15']);
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
  });

  it('runs a total of the hours logged on the workload milestones, counting time from before the range', () => {
    const daily = [
      { userId: 'ana', milestoneId: 'm1', day: '2026-10-01', hours: 2 },
      { userId: 'ana', milestoneId: 'm1', day: '2026-10-14', hours: 1.5 },
      { userId: 'ana', milestoneId: 'gone', day: '2026-10-14', hours: 9 },
      { userId: 'ben', milestoneId: 'm1', day: '2026-10-14', hours: 4 },
      { userId: 'ana', milestoneId: 'm2', day: '2026-10-15', hours: '0.5' },
    ];
    expect(progressSeries(daily, 'ana', new Set(['m1', 'm2']), ['2026-10-13', '2026-10-14', '2026-10-15'])).toEqual([
      { day: '2026-10-13', total: 2 },
      { day: '2026-10-14', total: 3.5 },
      { day: '2026-10-15', total: 4 },
    ]);
  });

  it('rounds the shared scale up to a clean number', () => {
    expect(niceMax(0)).toBe(8);
    expect(niceMax(37)).toBe(50);
    expect(niceMax(12)).toBe(20);
    expect(niceMax(250)).toBe(250);
  });
});
