import { describe, expect, it } from 'vitest';
import { formatDateRange, formatHours } from './format.js';

describe('formatHours', () => {
  it('shows hours and minutes, to the nearest minute', () => {
    expect(formatHours(12.5)).toBe('12 h 30 min');
    expect(formatHours(45)).toBe('45 h');
    expect(formatHours(0.25)).toBe('15 min');
    expect(formatHours(0.01)).toBe('1 min');
    expect(formatHours('3.01')).toBe('3 h 1 min');
    expect(formatHours(1.999)).toBe('2 h');
  });

  it('shows nothing logged as 0 min', () => {
    expect(formatHours(0)).toBe('0 min');
    expect(formatHours(undefined)).toBe('0 min');
  });
});

describe('formatDateRange', () => {
  it('writes the year once within a year, and in full across years', () => {
    expect(formatDateRange('2026-02-01', '2026-06-30')).toBe('Feb 1 – Jun 30, 2026');
    expect(formatDateRange('2026-12-01', '2027-01-31')).toBe('Dec 1, 2026 – Jan 31, 2027');
  });
});
