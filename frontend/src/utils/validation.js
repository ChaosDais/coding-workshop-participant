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
