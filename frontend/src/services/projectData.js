import { projectsApi, usersApi } from './api.js';

/**
 * Loads what the project screens need, as far as the signed-in user may see it. Admins get every project and
 * person, archived ones included, so names and dependency states can always be shown. Managers get their own
 * projects and their team, plus themselves so their own name resolves.
 * @param {{role: string}} user The signed-in user.
 * @returns {Promise<{active: object[], archived: object[], users: object[]}>} The data.
 */
export async function loadProjectsAndPeople(user) {
  if (user.role !== 'ADMIN') {
    const [active, team] = await Promise.all([projectsApi.list(false), usersApi.list(false)]);
    return { active, archived: [], users: [...team, user] };
  }
  const [active, archived, users, archivedUsers] = await Promise.all([
    projectsApi.list(false), projectsApi.list(true), usersApi.list(false), usersApi.list(true),
  ]);
  return { active, archived, users: [...users, ...archivedUsers] };
}
