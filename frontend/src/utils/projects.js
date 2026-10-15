/**
 * Whether a project can be worked on: completed, available, or blocked by projects it depends on.
 * The backend describes each dependency in project.dependencies, which works even for projects the viewer cannot
 * open; projectsById is the fallback for data without it.
 * @param {{status: string, dependsOn?: string[], dependencies?: {id: string, name: string, status: string}[]}} project
 *   The project.
 * @param {Map<string, {name: string, status: string}>} projectsById Every known project, archived ones included.
 * @returns {{state: 'completed'|'available'|'blocked', blockedBy: string[], label: string}} The availability, with
 *   the names of the dependencies that are not complete yet.
 */
export function availability(project, projectsById) {
  if (project.status === 'COMPLETE') {
    return { state: 'completed', blockedBy: [], label: 'Completed' };
  }
  const described = new Map((project.dependencies ?? []).filter((dependency) => dependency.name)
    .map((dependency) => [dependency.id, dependency]));
  const blockedBy = (project.dependsOn ?? [])
    .map((id) => described.get(id) ?? projectsById.get(id)
      ?? { name: 'an unknown project', status: 'UNKNOWN' })
    .filter((dependency) => dependency.status !== 'COMPLETE')
    .map((dependency) => dependency.name);
  return blockedBy.length === 0
    ? { state: 'available', blockedBy, label: 'Available' }
    : { state: 'blocked', blockedBy, label: `Blocked by ${blockedBy.join(', ')}` };
}

/**
 * The rows of the projects list: filtered, then ordered with open projects first, archived open ones next and
 * completed ones last, each group by start date and name. Completed projects are left out unless asked for or
 * the status filter picks them.
 * @param {object[]} active Active projects.
 * @param {object[]} archived Archived projects.
 * @param {{search?: string, status?: string, managerId?: string, showArchived?: boolean,
 *   showCompleted?: boolean}} filters "" or false means no filter.
 * @param {Map<string, {name: string}>} usersById Users, to search by manager name.
 * @returns {{project: object, archived: boolean, completed: boolean}[]} The rows.
 */
export function projectRows(active, archived, filters, usersById) {
  const {
    search = '', status = '', managerId = '', showArchived = false, showCompleted = false,
  } = filters;
  const needle = search.trim().toLowerCase();
  const rank = (row) => (row.completed ? 2 : 0) + (row.archived ? 1 : 0);
  return [
    ...active.map((project) => ({ project, archived: false })),
    ...(showArchived ? archived.map((project) => ({ project, archived: true })) : []),
  ]
    .map((row) => ({ ...row, completed: row.project.status === 'COMPLETE' }))
    .filter((row) => (status ? row.project.status === status : showCompleted || !row.completed))
    .filter((row) => !managerId || row.project.manager === managerId)
    .filter((row) => !needle || [row.project.name, row.project.description, usersById.get(row.project.manager)?.name]
      .some((value) => value?.toLowerCase().includes(needle)))
    .sort((a, b) => rank(a) - rank(b) || a.project.startDate.localeCompare(b.project.startDate)
      || a.project.name.localeCompare(b.project.name));
}

/**
 * The milestones a milestone is waiting for.
 * @param {{dependsOn?: string[]}} milestone The milestone.
 * @param {{id: string, name: string, status: string}[]} milestones Its project's milestones.
 * @returns {string[]} Names of the dependencies that are not complete yet.
 */
export function blockingMilestones(milestone, milestones) {
  const byId = new Map(milestones.map((other) => [other.id, other]));
  return (milestone.dependsOn ?? [])
    .map((id) => byId.get(id))
    .filter((dependency) => dependency && dependency.status !== 'COMPLETE')
    .map((dependency) => dependency.name);
}

/**
 * Which unfinished milestone of the project each employee is working on, leaving out one milestone (the one being
 * edited). An employee works on at most one unfinished milestone per project.
 * @param {{milestones: {id: string, name: string, status: string, assignedEmployees: string[]}[]}} project Project.
 * @param {string} [exceptMilestoneId] The milestone to leave out.
 * @returns {Map<string, string>} Milestone name by employee id.
 */
export function openMilestoneByEmployee(project, exceptMilestoneId) {
  const working = new Map();
  project.milestones
    .filter((milestone) => milestone.id !== exceptMilestoneId && milestone.status !== 'COMPLETE')
    .forEach((milestone) => milestone.assignedEmployees.forEach((id) => working.set(id, milestone.name)));
  return working;
}

/**
 * Projects ordered for the list: by start date, then name.
 * @param {object[]} projects Projects.
 * @returns {object[]} A sorted copy.
 */
export function sortProjects(projects) {
  return [...projects].sort((a, b) => a.startDate.localeCompare(b.startDate) || a.name.localeCompare(b.name));
}

/**
 * Compares logged progress with the expected hours.
 * @param {number} progress Hours logged so far.
 * @param {number|undefined|null} expected Expected hours, if set.
 * @returns {{expected: boolean, over: boolean, overBy: number, percent: number|undefined}} Whether there is an
 *   estimate, whether progress has passed it and by how many hours, and the completion percentage (progress as a
 *   whole percentage of the estimate; it can pass 100).
 */
export function hoursAgainstExpected(progress, expected) {
  if (expected === undefined || expected === null) {
    return {
      expected: false, over: false, overBy: 0, percent: undefined,
    };
  }
  const logged = Number(progress ?? 0);
  const estimate = Number(expected);
  const overBy = Math.round((logged - estimate) * 100) / 100;
  return {
    expected: true,
    over: overBy > 0,
    overBy: Math.max(overBy, 0),
    percent: estimate === 0 ? (logged > 0 ? undefined : 0) : Math.round((logged / estimate) * 100),
  };
}

/**
 * How long something may sit in one status before it is flagged as a possible bottleneck. Admins change these in
 * Options → System; these are the defaults the backend also starts from.
 */
export const DEFAULT_BOTTLENECK_THRESHOLDS = { awaitingReviewDays: 3, anyStatusDays: 30 };
const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * How long a project or milestone has been in its current status, and whether that suggests a bottleneck.
 * Completed work and records without a known status-change time give undefined.
 * @param {{status: string, statusChangedAt?: string, startDate: string, endDate: string}} item Project or milestone.
 * @param {number} now Current time in milliseconds.
 * @param {{awaitingReviewDays: number, anyStatusDays: number}} [thresholds] When time in a status is notable.
 * @returns {{days: number, notable: boolean, reason: string|undefined}|undefined} Whole days in the status, and
 *   why it stands out: waiting for review too long, not started by its start date, past its expected end, or
 *   simply in one status for a long time.
 */
export function timeInStatus(item, now, thresholds = DEFAULT_BOTTLENECK_THRESHOLDS) {
  if (item.status === 'COMPLETE' || !item.statusChangedAt) {
    return undefined;
  }
  const days = Math.max(0, Math.floor((now - Date.parse(item.statusChangedAt)) / DAY_MS));
  const today = new Date(now).toISOString().slice(0, 10);
  const daysPast = (date) => Math.round((Date.parse(today) - Date.parse(date)) / DAY_MS);
  let reason;
  if (item.status === 'AWAITING_REVIEW' && days >= thresholds.awaitingReviewDays) {
    reason = `Waiting for review for ${days} days`;
  } else if (item.endDate && today > item.endDate) {
    reason = `Past its expected end by ${daysPast(item.endDate)} days`;
  } else if (item.status === 'TODO' && item.startDate && today > item.startDate) {
    reason = `Not started, ${daysPast(item.startDate)} days after its start date`;
  } else if (days >= thresholds.anyStatusDays) {
    reason = `In this status for ${days} days`;
  }
  return { days, notable: Boolean(reason), reason };
}

/**
 * Each employee's workload: their share of the expected hours of the unfinished milestones they are assigned to.
 * A milestone's expected hours are split evenly between everyone assigned to it. Complete milestones and
 * milestones of complete projects do not count; milestones without expected hours are counted separately.
 * @param {{status: string, milestones: {id: string, status: string, expectedHours?: number,
 *   assignedEmployees: string[]}[]}[]} projects Active projects.
 * @returns {Map<string, {hours: number, milestones: number, unestimated: number, shares: Map<string, number>}>}
 *   By employee id: total hours, how many milestones count, how many lack an estimate, and the share of each
 *   estimated milestone by milestone id.
 */
export function workloadByEmployee(projects) {
  const workloads = new Map();
  projects.filter((project) => project.status !== 'COMPLETE').forEach((project) => {
    project.milestones.filter((milestone) => milestone.status !== 'COMPLETE').forEach((milestone) => {
      const people = milestone.assignedEmployees;
      const estimated = milestone.expectedHours !== undefined && milestone.expectedHours !== null;
      people.forEach((id) => {
        const load = workloads.get(id) ?? {
          hours: 0, milestones: 0, unestimated: 0, shares: new Map(),
        };
        load.milestones += 1;
        if (estimated) {
          const share = Number(milestone.expectedHours) / people.length;
          load.hours += share;
          load.shares.set(milestone.id, share);
        } else {
          load.unestimated += 1;
        }
        workloads.set(id, load);
      });
    });
  });
  return workloads;
}
