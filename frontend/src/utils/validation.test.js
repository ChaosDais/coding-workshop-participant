import { describe, expect, it } from 'vitest';
import {
  compact, dateRange, email, nonNegativeNumber, password, required,
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
