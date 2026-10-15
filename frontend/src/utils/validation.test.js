import { describe, expect, it } from 'vitest';
import {
  compact, dateRange, email, endBeforeDependents, expectedHoursFit, nonNegativeNumber, password, possibleHours, required, startAfterDependencies, startNotPast,
} from './validation.js';

describe('validation', () => {
  it('checks required text', () => {
    expect(required('  ', 'Name')).toBe('Name is required');
    expect(required('Apollo', 'Name')).toBeUndefined();
  });

  it('checks emails', () => {
    expect(email('')).toBe('Email is required');
    expect(email('not-an-email')).toBe('Enter a valid email address');
    expect(email('a@acme.com')).toBeUndefined();
  });

  it('checks numbers of zero or more, optionally empty', () => {
    expect(nonNegativeNumber('-1', 'Budget')).toBe('Budget must be a number of 0 or more');
    expect(nonNegativeNumber('', 'Budget')).toBe('Budget is required');
    expect(nonNegativeNumber('', 'Salary', { optional: true })).toBeUndefined();
    expect(nonNegativeNumber('0', 'Budget')).toBeUndefined();
  });

  it('checks date ranges', () => {
    expect(dateRange('2026-02-01', '2026-01-01')).toEqual({ end: 'End date must not be before the start date' });
    expect(dateRange('', '')).toEqual({ start: 'Start date is required', end: 'End date is required' });
    expect(dateRange('2026-01-01', '2026-01-01')).toEqual({});
  });

  it('checks passwords like the backend does', () => {
    expect(password('short')).toBe('Password must be at least 8 characters');
    expect(password('x'.repeat(73))).toBe('Password must be at most 72 bytes');
    expect(password('long enough')).toBeUndefined();
  });

  it('drops fields without an error', () => {
    expect(compact({ a: undefined, b: 'bad' })).toEqual({ b: 'bad' });
  });
});

describe('scheduling rules', () => {
  it('refuses a start date in the past, unless it is the one already saved', () => {
    expect(startNotPast('2026-10-14', undefined, '2026-10-15')).toBe("Start date can't be in the past (today is 2026-10-15)");
    expect(startNotPast('2026-10-15', undefined, '2026-10-15')).toBeUndefined();
    expect(startNotPast('2026-09-01', '2026-09-01', '2026-10-15')).toBeUndefined();
  });

  it('allows 24 hours a day per person, both dates included', () => {
    expect(possibleHours('2026-11-02', '2026-11-03', 0)).toBe(48);
    expect(possibleHours('2026-11-02', '2026-11-03', 2)).toBe(96);
    expect(expectedHoursFit('48', '2026-11-02', '2026-11-03', 1, 'assigned employees')).toBeUndefined();
    expect(expectedHoursFit('49', '2026-11-02', '2026-11-03', 1, 'assigned employees'))
      .toBe('More than is possible: at most 48 h (24 h a day × 2 days × 1 person, counting assigned employees)');
    expect(expectedHoursFit('', '2026-11-02', '2026-11-03', 1, 'assigned employees')).toBeUndefined();
  });

  it('starts after unfinished dependencies end, and keeps ends before dependents start', () => {
    const design = { name: 'Design', endDate: '2026-11-30', status: 'IN_PROGRESS' };
    expect(startAfterDependencies('2026-11-15', [design])).toBe('Must be on or after Design ends (2026-11-30)');
    expect(startAfterDependencies('2026-11-30', [design])).toBeUndefined();
    expect(startAfterDependencies('2026-11-15', [{ ...design, status: 'COMPLETE' }])).toBeUndefined();

    const build = { name: 'Build', startDate: '2026-11-30', status: 'TODO' };
    expect(endBeforeDependents('2026-12-10', [build])).toBe('Must be on or before Build, which depends on it, starts (2026-11-30)');
    expect(endBeforeDependents('2026-11-30', [build])).toBeUndefined();
  });
});
