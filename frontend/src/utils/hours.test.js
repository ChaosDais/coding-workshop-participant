import { describe, expect, it } from 'vitest';
import { hoursAgainstExpected } from './projects.js';

describe('hoursAgainstExpected', () => {
  it('gives the completion percentage of the expected hours', () => {
    expect(hoursAgainstExpected(12.5, 40)).toEqual({
      expected: true, over: false, overBy: 0, percent: 31,
    });
  });

  it('flags progress beyond the estimate and by how much', () => {
    expect(hoursAgainstExpected(42.5, 40)).toEqual({
      expected: true, over: true, overBy: 2.5, percent: 106,
    });
    expect(hoursAgainstExpected(40, 40).over).toBe(false);
  });

  it('has nothing to compare without an estimate', () => {
    expect(hoursAgainstExpected(5, undefined)).toEqual({
      expected: false, over: false, overBy: 0, percent: undefined,
    });
    expect(hoursAgainstExpected(0, 0).percent).toBe(0);
    expect(hoursAgainstExpected(1, 0)).toMatchObject({ over: true, percent: undefined });
  });
});
