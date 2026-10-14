import { projectsApi, usersApi } from './api.js';

/**
 * Loads everything the admin's project screens need: projects and people, archived ones included, so names and
 * dependency states can always be shown.
 * @returns {Promise<{active: object[], archived: object[], users: object[]}>} The data.
 */
export async function loadProjectsAndPeople() {
  const [active, archived, users, archivedUsers] = await Promise.all([
    projectsApi.list(false), projectsApi.list(true), usersApi.list(false), usersApi.list(true),
  ]);
  return { active, archived, users: [...users, ...archivedUsers] };
}
