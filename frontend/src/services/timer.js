/** A timer covers at most one 8-hour workday; the backend closes it at this length (TimeService.MAX_TIMER). */
export const MAX_TIMER_MS = 8 * 60 * 60 * 1000;

/**
 * Time a timer has run, never more than the 8-hour cap and never negative.
 * @param {string} startedAt ISO timestamp from the backend.
 * @param {number} now Current time in milliseconds.
 * @returns {number} Elapsed milliseconds.
 */
export function elapsedMs(startedAt, now) {
  const elapsed = now - Date.parse(startedAt);
  return Math.min(Math.max(elapsed, 0), MAX_TIMER_MS);
}

/**
 * Formats a duration as H:MM:SS, e.g. 1:05:09.
 * @param {number} ms Duration in milliseconds.
 * @returns {string} Formatted duration.
 */
export function formatDuration(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}
