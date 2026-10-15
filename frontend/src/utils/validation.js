/**
 * Client-side checks that mirror the backend's, so mistakes show next to the field before anything is sent.
 * Each returns an error message, or undefined when the value is fine.
 */

export function required(value, label) {
  return String(value ?? '').trim() ? undefined : `${label} is required`;
}

export function email(value) {
  const text = String(value ?? '').trim();
  if (!text) {
    return 'Email is required';
  }
  return /^[^\s@]+@[^\s@]+$/.test(text) ? undefined : 'Enter a valid email address';
}

export function nonNegativeNumber(value, label, { optional = false } = {}) {
  if (value === '' || value === undefined || value === null) {
    return optional ? undefined : `${label} is required`;
  }
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? undefined : `${label} must be a number of 0 or more`;
}

export function dateRange(start, end) {
  if (!start || !end) {
    return { start: start ? undefined : 'Start date is required', end: end ? undefined : 'End date is required' };
  }
  return end < start ? { end: 'End date must not be before the start date' } : {};
}

export function password(value) {
  const text = String(value ?? '');
  if (text.length < 8) {
    return 'Password must be at least 8 characters';
  }
  return new TextEncoder().encode(text).length > 72 ? 'Password must be at most 72 bytes' : undefined;
}

/** Drops the fields without an error. */
export function compact(errors) {
  return Object.fromEntries(Object.entries(errors).filter(([, message]) => message));
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * A start date must not be in the past, unless it is the date already saved (something that has started).
 * @param {string} start "2026-11-01".
 * @param {string|undefined} saved The start date already saved, if editing.
 * @param {string} today "2026-10-15".
 * @returns {string|undefined} The error, if any.
 */
export function startNotPast(start, saved, today) {
  return start && start !== saved && start < today ? `Start date can't be in the past (today is ${today})` : undefined;
}

/**
 * The hours that fit between two dates, both included, for a number of people working 24 hours a day.
 * @param {string} start "2026-11-01".
 * @param {string} end "2026-11-03".
 * @param {number} people How many people; at least one is counted.
 * @returns {number} e.g. 72 for three days and one person.
 */
export function possibleHours(start, end, people) {
  const days = Math.round((Date.parse(end) - Date.parse(start)) / DAY_MS) + 1;
  return 24 * days * Math.max(1, people);
}

/**
 * Expected hours must fit in 24 hours a day per person between the start and end dates.
 * @param {string} expected The expected hours as typed; empty means none.
 * @param {string} start Start date.
 * @param {string} end End date.
 * @param {number} people How many people work on it.
 * @param {string} whoCounts Who is counted, for the message, e.g. "assigned employees".
 * @returns {string|undefined} The error, if any.
 */
export function expectedHoursFit(expected, start, end, people, whoCounts) {
  if (expected === '' || expected === undefined || expected === null || !start || !end || end < start) {
    return undefined;
  }
  const possible = possibleHours(start, end, people);
  if (Number(expected) <= possible) {
    return undefined;
  }
  const days = possible / 24 / Math.max(1, people);
  const counted = Math.max(1, people);
  return `More than is possible: at most ${possible} h (24 h a day × ${days} ${days === 1 ? 'day' : 'days'} × ${counted} ${counted === 1 ? 'person' : 'people'}, counting ${whoCounts})`;
}

/**
 * Something cannot start before the unfinished things it depends on end; starting the day they end is fine.
 * @param {string} start Its start date.
 * @param {{name: string, endDate: string, status: string}[]} dependencies What it depends on.
 * @returns {string|undefined} The error, if any.
 */
export function startAfterDependencies(start, dependencies) {
  const blocking = dependencies.find((dependency) => dependency.status !== 'COMPLETE' && start && start < dependency.endDate);
  return blocking ? `Must be on or after ${blocking.name} ends (${blocking.endDate})` : undefined;
}

/**
 * Something's end cannot move past the start of unfinished things that depend on it.
 * @param {string} end Its end date.
 * @param {{name: string, startDate: string, status: string}[]} dependents What depends on it.
 * @returns {string|undefined} The error, if any.
 */
export function endBeforeDependents(end, dependents) {
  const waiting = dependents.find((dependent) => dependent.status !== 'COMPLETE' && end && end > dependent.startDate);
  return waiting ? `Must be on or before ${waiting.name}, which depends on it, starts (${waiting.startDate})` : undefined;
}
