const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

/** @param {number|string|undefined} value Amount. @returns {string} "$1,234.50", or "—" when absent. */
export function formatMoney(value) {
  if (value === undefined || value === null || value === '') {
    return '—';
  }
  return money.format(Number(value));
}

/**
 * Hours as hours and minutes, to the nearest minute.
 * @param {number|string|undefined} hours Hours, e.g. 12.5.
 * @returns {string} "12 h 30 min", "45 h", "20 min" or "0 min".
 */
export function formatHours(hours) {
  const minutes = Math.round(Math.abs(Number(hours ?? 0)) * 60);
  const sign = Number(hours) < 0 && minutes > 0 ? '−' : '';
  const whole = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (whole === 0) {
    return `${sign}${rest} min`;
  }
  return `${sign}${whole} h${rest ? ` ${rest} min` : ''}`;
}

/** @param {string|undefined} date "2026-01-31". @returns {string} "Jan 31, 2026", or "—". */
export function formatDate(date) {
  if (!date) {
    return '—';
  }
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString('en-US', {
    year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC',
  });
}

/** @param {string|undefined} timestamp ISO instant. @returns {string} Local date and time, or "—". */
export function formatTimestamp(timestamp) {
  if (!timestamp) {
    return '—';
  }
  return new Date(timestamp).toLocaleString('en-US', {
    year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

const STATUS_LABELS = {
  TODO: 'To do', IN_PROGRESS: 'In progress', AWAITING_REVIEW: 'Awaiting review', COMPLETE: 'Complete',
};

export const STATUSES = Object.keys(STATUS_LABELS);

/** @param {string} status e.g. "IN_PROGRESS". @returns {string} "In progress". */
export function statusLabel(status) {
  return STATUS_LABELS[status] ?? status;
}

const ROLE_LABELS = { ADMIN: 'Admin', MANAGER: 'Manager', EMPLOYEE: 'Employee' };

export const ROLES = Object.keys(ROLE_LABELS);

/** @param {string} role e.g. "MANAGER". @returns {string} "Manager". */
export function roleLabel(role) {
  return ROLE_LABELS[role] ?? role;
}
