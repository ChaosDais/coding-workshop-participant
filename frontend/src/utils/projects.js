/**
 * Whether a project can be worked on: completed, available, or blocked by projects it depends on.
 * @param {{status: string, dependsOn?: string[]}} project The project.
 * @param {Map<string, {name: string, status: string}>} projectsById Every known project, archived ones included.
 * @returns {{state: 'completed'|'available'|'blocked', blockedBy: string[], label: string}} The availability, with
 *   the names of the dependencies that are not complete yet.
 */
export function availability(project, projectsById) {
  if (project.status === 'COMPLETE') {
    return { state: 'completed', blockedBy: [], label: 'Completed' };
  }
  const blockedBy = (project.dependsOn ?? [])
    .map((id) => projectsById.get(id) ?? { name: `Unknown project (${id.slice(0, 8)})`, status: 'UNKNOWN' })
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
 * Projects ordered for the list: by start date, then name.
 * @param {object[]} projects Projects.
 * @returns {object[]} A sorted copy.
 */
export function sortProjects(projects) {
  return [...projects].sort((a, b) => a.startDate.localeCompare(b.startDate) || a.name.localeCompare(b.name));
}
