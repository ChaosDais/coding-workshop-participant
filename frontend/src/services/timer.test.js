import { describe, expect, it } from 'vitest';
import { MAX_TIMER_MS, elapsedMs, formatDuration } from './timer.js';

const start = '2026-10-14T09:00:00Z';
const at = (iso) => Date.parse(iso);

describe('elapsedMs', () => {
  it('counts the time since the timer started', () => {
    expect(elapsedMs(start, at('2026-10-14T10:30:15Z'))).toBe(((90 * 60) + 15) * 1000);
  });

  it('stops at the 8-hour cap', () => {
    expect(elapsedMs(start, at('2026-10-15T09:00:00Z'))).toBe(MAX_TIMER_MS);
  });

  it('is never negative when the local clock is behind the server', () => {
    expect(elapsedMs(start, at('2026-10-14T08:59:58Z'))).toBe(0);
  });
});

describe('formatDuration', () => {
  it('pads minutes and seconds', () => {
    expect(formatDuration((((1 * 60) + 5) * 60 + 9) * 1000)).toBe('1:05:09');
  });

  it('drops partial seconds', () => {
    expect(formatDuration(59_999)).toBe('0:00:59');
  });

  it('shows the full cap', () => {
    expect(formatDuration(MAX_TIMER_MS)).toBe('8:00:00');
  });
});
