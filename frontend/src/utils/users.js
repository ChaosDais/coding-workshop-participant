/**
 * Filters people by a search text (name, email, job, id) and a role.
 * @param {object[]} users Users.
 * @param {{search?: string, role?: string}} filters "" or undefined means no filter.
 * @returns {object[]} Matching users, sorted by name.
 */
export function filterUsers(users, { search = '', role = '' } = {}) {
  const needle = search.trim().toLowerCase();
  return users
    .filter((user) => !role || user.role === role)
    .filter((user) => !needle || [user.name, user.email, user.jobType, user.id]
      .some((value) => value?.toLowerCase().includes(needle)))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** @param {object[]} users Users. @returns {Map<string, object>} Users by id. */
export function byId(users) {
  return new Map(users.map((user) => [user.id, user]));
}
