import { describe, expect, it } from 'vitest';
import { timeInStatus } from './projects.js';

const now = Date.parse('2026-10-14T12:00:00Z');
const item = (status, statusChangedAt, extra = {}) => ({
  status, statusChangedAt, startDate: '2026-10-01', endDate: '2026-12-31', ...extra,
});

describe('timeInStatus', () => {
  it('counts whole days in the current status', () => {
    expect(timeInStatus(item('IN_PROGRESS', '2026-10-04T12:00:00Z'), now)).toEqual({
      days: 10, notable: false, reason: undefined,
    });
    expect(timeInStatus(item('IN_PROGRESS', '2026-10-14T08:00:00Z'), now).days).toBe(0);
  });

  it('shows nothing for completed work or an unknown start of the status', () => {
    expect(timeInStatus(item('COMPLETE', '2026-01-01T00:00:00Z'), now)).toBeUndefined();
    expect(timeInStatus(item('TODO', undefined), now)).toBeUndefined();
  });

  it('flags a review that has waited three days or more', () => {
    expect(timeInStatus(item('AWAITING_REVIEW', '2026-10-12T12:00:00Z'), now).notable).toBe(false);
    expect(timeInStatus(item('AWAITING_REVIEW', '2026-10-11T12:00:00Z'), now).reason)
      .toBe('Waiting for review for 3 days');
  });

  it('flags work not started by its start date, and work past its expected end', () => {
    expect(timeInStatus(item('TODO', '2026-10-10T12:00:00Z'), now).reason)
      .toBe('Not started, 13 days after its start date');
    expect(timeInStatus(item('TODO', '2026-10-10T12:00:00Z', { startDate: '2026-11-01' }), now).notable).toBe(false);
    expect(timeInStatus(item('IN_PROGRESS', '2026-10-10T12:00:00Z', { endDate: '2026-10-04' }), now).reason)
      .toBe('Past its expected end by 10 days');
  });

  it('flags anything in one status for 30 days or more', () => {
    expect(timeInStatus(item('IN_PROGRESS', '2026-09-14T12:00:00Z'), now).reason).toBe('In this status for 30 days');
    expect(timeInStatus(item('IN_PROGRESS', '2026-09-15T12:00:00Z'), now).notable).toBe(false);
  });

  it('uses the thresholds an admin has set', () => {
    const thresholds = { awaitingReviewDays: 1, anyStatusDays: 7 };
    expect(timeInStatus(item('AWAITING_REVIEW', '2026-10-13T12:00:00Z'), now, thresholds).notable).toBe(true);
    expect(timeInStatus(item('IN_PROGRESS', '2026-10-07T12:00:00Z'), now, thresholds).reason)
      .toBe('In this status for 7 days');
  });
});
