const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

/** @param {number|string|undefined} value Amount. @returns {string} "$1,234.50", or "—" when absent. */
export function formatMoney(value) {
  if (value === undefined || value === null || value === '') {
    return '—';
  }
  return money.format(Number(value));
}

/** @param {number|undefined} hours Hours. @returns {string} "12.5 h". */
export function formatHours(hours) {
  const value = Number(hours ?? 0);
  return `${Number.isInteger(value) ? value : value.toFixed(2).replace(/0$/, '')} h`;
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
