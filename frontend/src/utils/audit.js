import { formatHours } from './format.js';

/** Audit actions grouped the way the audit log's type filter offers them. */
export const ACTION_GROUPS = {
  Accounts: ['default_admin_created', 'user_created', 'user_updated', 'user_archived', 'user_restored',
    'employee_moved', 'password_reset', 'password_changed', 'profile_updated'],
  Projects: ['project_created', 'project_updated', 'project_archived', 'project_restored'],
  Milestones: ['milestone_added', 'milestone_updated', 'milestone_status_changed', 'milestone_archived',
    'employee_assigned', 'employee_unassigned', 'employee_reassigned'],
  Time: ['time_logged', 'timer_started', 'timer_stopped', 'timer_capped', 'time_corrected'],
};

/** @param {string} action e.g. "milestone_status_changed". @returns {string} "Milestone status changed". */
export function actionLabel(action) {
  const words = action.replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** A user's name from the entry, else from the lookup; never their id. */
function userName(id, storedName, users) {
  return storedName ?? users?.get(id)?.name ?? 'an unknown user';
}

/**
 * Who made the change, by name: stored with the entry, else looked up by the email the entry records.
 * @param {{actor: string, details?: object}} entry Audit entry.
 * @param {{users?: Map<string, {name: string, email: string}>}} names Lookups.
 * @returns {string} e.g. "Ada Lovelace", or the email or "system" when no name is known.
 */
export function actorName(entry, names = {}) {
  if (entry.details?.actorName) {
    return entry.details.actorName;
  }
  const found = [...(names.users?.values() ?? [])].find((user) => user.email === entry.actor);
  return found?.name ?? entry.actor;
}

/**
 * A short, readable summary of an entry's details. Names stored in the entry come first, since they record what
 * something was called at the time; entries written before names were stored fall back to the lookups.
 * @param {{details: object}} entry Audit entry.
 * @param {{users?: Map<string, {name: string}>, milestones?: Map<string, {name: string}>}} names Lookups.
 * @returns {string} e.g. "Apollo: Design · TODO → COMPLETE" (project: milestone).
 */
export function describeDetails(entry, names = {}) {
  const details = entry.details ?? {};
  const parts = [];
  // "Project: Milestone" for changes to a milestone, or just the project's name.
  const milestone = details.milestoneId
    ? details.milestoneName ?? names.milestones?.get(details.milestoneId)?.name ?? 'Unknown milestone'
    : undefined;
  if (details.projectName && milestone) {
    parts.push(`${details.projectName}: ${milestone}`);
  } else if (details.projectName || milestone) {
    parts.push(details.projectName ?? milestone);
  }
  if (details.userId) {
    const name = userName(details.userId, details.name, names.users);
    parts.push(details.email ? `${name} (${details.email})` : name);
  } else if (details.email) {
    parts.push(details.email);
  }
  if ('toManagerId' in details) {
    const manager = (id, storedName) => (id ? userName(id, storedName, names.users) : 'no manager');
    parts.push(`${manager(details.fromManagerId, details.fromManagerName)} → ${manager(details.toManagerId, details.toManagerName)}`);
  }
  if (details.employeeId) {
    parts.push(details.employeeName ?? names.users?.get(details.employeeId)?.name
      ?? 'an unknown employee');
  }
  if (details.fromMilestoneName) {
    parts.push(`from ${details.fromMilestoneName}`);
  }
  if (details.hours) {
    parts.push(details.previousHours ? `${formatHours(details.previousHours)} → ${formatHours(details.hours)}` : formatHours(details.hours));
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
