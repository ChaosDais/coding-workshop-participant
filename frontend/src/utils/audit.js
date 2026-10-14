/** Audit actions grouped the way the audit log's type filter offers them. */
export const ACTION_GROUPS = {
  Accounts: ['default_admin_created', 'user_created', 'user_updated', 'user_archived', 'user_restored',
    'employee_moved', 'password_reset', 'password_changed', 'profile_updated'],
  Projects: ['project_created', 'project_updated', 'project_archived', 'project_restored'],
  Milestones: ['milestone_added', 'milestone_updated', 'milestone_status_changed', 'milestone_archived'],
  Time: ['time_logged', 'timer_started', 'timer_stopped', 'timer_capped', 'time_corrected'],
};

/** @param {string} action e.g. "milestone_status_changed". @returns {string} "Milestone status changed". */
export function actionLabel(action) {
  const words = action.replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** A user's name from the entry, else from the lookup, else a short id. */
function userName(id, storedName, users) {
  return storedName ?? users?.get(id)?.name ?? `user ${id.slice(0, 8)}`;
}

/**
 * A short, readable summary of an entry's details. Names stored in the entry come first, since they record what
 * something was called at the time; entries written before names were stored fall back to the lookups.
 * @param {{details: object}} entry Audit entry.
 * @param {{users?: Map<string, {name: string}>, milestones?: Map<string, {name: string}>}} names Lookups.
 * @returns {string} e.g. "Apollo · milestone Design · TODO → COMPLETE".
 */
export function describeDetails(entry, names = {}) {
  const details = entry.details ?? {};
  const parts = [];
  if (details.projectName) {
    parts.push(details.projectName);
  }
  if (details.milestoneId) {
    const name = details.milestoneName ?? names.milestones?.get(details.milestoneId)?.name;
    parts.push(`milestone ${name ?? details.milestoneId.slice(0, 8)}`);
  }
  if (details.userId) {
    const name = userName(details.userId, details.name, names.users);
    parts.push(details.email ? `${name} (${details.email})` : name);
  } else if (details.email) {
    parts.push(details.email);
  }
  if ('toManagerId' in details) {
    const manager = (id) => (id ? userName(id, undefined, names.users) : 'no manager');
    parts.push(`${manager(details.fromManagerId)} → ${manager(details.toManagerId)}`);
  }
  if (details.employeeId) {
    const employee = names.users?.get(details.employeeId);
    parts.push(employee ? employee.name : `employee ${details.employeeId.slice(0, 8)}`);
  }
  if (details.hours) {
    parts.push(details.previousHours ? `${details.previousHours} h → ${details.hours} h` : `${details.hours} h`);
  }
  if (details.from && details.to) {
    parts.push(`${details.from} → ${details.to}`);
  }
  if (details.role) {
    parts.push(details.role.toLowerCase());
  }
  if (details.reason) {
    parts.push(`“${details.reason}”`);
  }
  return parts.join(' · ');
}
