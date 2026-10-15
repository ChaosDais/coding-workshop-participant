import { buildUrl, parseJson } from './apiClient.js';

const SERVICE = 'workshop-service';
const STORAGE_KEY = 'acme.session';

/** An HTTP error from the backend, carrying its status and the server's message. */
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/**
 * The signed-in session: { accessToken, refreshToken, user }. Kept in sessionStorage so a page reload stays signed
 * in, but closing the tab signs out.
 */
let session = readStoredSession();
const listeners = new Set();

function readStoredSession() {
  try {
    return JSON.parse(sessionStorage.getItem(STORAGE_KEY)) ?? undefined;
  } catch {
    return undefined;
  }
}

/**
 * Replaces the session and tells subscribers; undefined signs out.
 * @param {object|undefined} next New session.
 */
export function setSession(next) {
  session = next;
  try {
    if (next) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Storage can be unavailable (private mode); the session then lasts until reload.
  }
  listeners.forEach((listener) => listener(next));
}

export function getSession() {
  return session;
}

/**
 * Calls listener with the new session whenever it changes.
 * @param {Function} listener Receives the session or undefined.
 * @returns {Function} Unsubscribes.
 */
export function onSessionChange(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

async function send(method, path, body, accessToken) {
  const headers = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  let response;
  try {
    response = await fetch(buildUrl(SERVICE, path), {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check your connection and try again.');
  }
  const text = await response.text();
  const data = text ? parseJson(text) : undefined;
  if (response.ok && text && data === undefined) {
    // On AWS, CloudFront answers an API 404 with the app's index page and status 200; treat it as the 404 it was.
    throw new ApiError(404, 'Not found: it may have been archived or removed.');
  }
  if (!response.ok) {
    throw new ApiError(response.status, data?.error ?? (response.statusText || 'Request failed'));
  }
  return data;
}

let refreshing;

/** Swaps the refresh token for new tokens once, even when several requests need it at the same moment. */
export function refreshSession() {
  if (!refreshing) {
    const current = session;
    refreshing = (current?.refreshToken
      ? send('POST', '/auth/refresh', { refreshToken: current.refreshToken })
        .then((next) => setSession({
          accessToken: next.accessToken, refreshToken: next.refreshToken, user: next.user,
        }))
        .catch((error) => {
          setSession(undefined);
          throw error;
        })
      : Promise.reject(new ApiError(401, 'Not signed in')))
      .finally(() => { refreshing = undefined; });
  }
  return refreshing;
}

/**
 * Sends an authenticated request; on an expired token, refreshes once and retries.
 * @param {string} method HTTP method.
 * @param {string} path Path below the service, e.g. "/projects".
 * @param {object} [body] JSON body.
 * @returns {Promise<*>} The parsed response body.
 */
export async function request(method, path, body) {
  try {
    return await send(method, path, body, session?.accessToken);
  } catch (error) {
    if (error.status !== 401 || !session?.refreshToken) {
      throw error;
    }
    await refreshSession();
    return send(method, path, body, session?.accessToken);
  }
}

/** Builds "?a=1&b=2" from the defined, non-empty values. */
export function queryString(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '' && value !== false) {
      search.set(key, String(value));
    }
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export const auth = {
  async signIn(email, password) {
    const result = await send('POST', '/auth/login', { email, password });
    setSession({ accessToken: result.accessToken, refreshToken: result.refreshToken, user: result.user });
    return result.user;
  },
  async signOut() {
    const current = session;
    setSession(undefined);
    if (current?.refreshToken) {
      await send('POST', '/auth/logout', { refreshToken: current.refreshToken }).catch(() => {});
    }
  },
  async changePassword(currentPassword, newPassword) {
    await request('POST', '/auth/password', { currentPassword, newPassword });
    // Changing the password revokes every refresh token, so sign in again with the new one.
    const email = session?.user?.email;
    return email ? auth.signIn(email, newPassword) : undefined;
  },
  me: () => request('GET', '/auth/me'),
  /** Changes the signed-in user's own name and email, and keeps the session's copy in step. */
  async updateProfile(name, email) {
    const updated = await request('PUT', '/auth/me', { name, email });
    if (session) {
      setSession({ ...session, user: updated });
    }
    return updated;
  },
};

export const timeApi = {
  /** Logs hours worked on a milestone by hand: 0 < hours <= 24. */
  log: (projectId, milestoneId, hours, note) => request('POST',
    `/projects/${projectId}/milestones/${milestoneId}/time`, { hours, note: note || undefined }),
  /** Time logged on a milestone: everyone's for admins and managers, your own for employees. */
  list: (projectId, milestoneId) => request('GET', `/projects/${projectId}/milestones/${milestoneId}/time`),
  /** Your running timer, or undefined when none runs. */
  async current() {
    try {
      return await request('GET', '/timer');
    } catch (error) {
      if (error.status === 404) {
        return undefined;
      }
      throw error;
    }
  },
  start: (projectId, milestoneId) => request('POST', `/projects/${projectId}/milestones/${milestoneId}/timer`),
  stop: () => request('POST', '/timer/stop'),
  /** A person's assignments and logged hours by project and milestone. */
  forUser: (userId) => request('GET', `/users/${userId}/time`),
};

export const settingsApi = {
  bottlenecks: () => request('GET', '/settings/bottlenecks'),
  updateBottlenecks: (thresholds) => request('PUT', '/settings/bottlenecks', thresholds),
};

export const projectsApi = {
  list: (archived = false) => request('GET', `/projects${queryString({ archived })}`),
  get: (id) => request('GET', `/projects/${id}`),
  create: (project) => request('POST', '/projects', project),
  update: (id, project) => request('PUT', `/projects/${id}`, project),
  archive: (id) => request('DELETE', `/projects/${id}`),
  restore: (id) => request('POST', `/projects/${id}/restore`),
  milestones: (id, archived = false) => request('GET', `/projects/${id}/milestones${queryString({ archived })}`),
  addMilestone: (id, milestone) => request('POST', `/projects/${id}/milestones`, milestone),
  updateMilestone: (id, milestoneId, milestone) => request('PUT', `/projects/${id}/milestones/${milestoneId}`,
    milestone),
  setMilestoneStatus: (id, milestoneId, status) => request('PUT',
    `/projects/${id}/milestones/${milestoneId}/status`, { status }),
  archiveMilestone: (id, milestoneId) => request('DELETE', `/projects/${id}/milestones/${milestoneId}`),
  /** These change only who is assigned, so they never overwrite progress logged at the same time. */
  assign: (id, milestoneId, employeeId) => request('POST', `/projects/${id}/milestones/${milestoneId}/assignees`,
    { employeeId }),
  unassign: (id, milestoneId, employeeId) => request('DELETE',
    `/projects/${id}/milestones/${milestoneId}/assignees/${employeeId}`),
  move: (id, milestoneId, employeeId, toMilestoneId) => request('POST',
    `/projects/${id}/milestones/${milestoneId}/assignees/${employeeId}/move`, { toMilestoneId }),
};

export const usersApi = {
  list: (archived = false) => request('GET', `/users${queryString({ archived })}`),
  create: (user) => request('POST', '/users', user),
  update: (id, user) => request('PUT', `/users/${id}`, user),
  archive: (id) => request('DELETE', `/users/${id}`),
  restore: (id) => request('POST', `/users/${id}/restore`),
  get: (id) => request('GET', `/users/${id}`),
  /** Sets someone else's password; they are signed out of every session. */
  resetPassword: (id, password) => request('PUT', `/users/${id}/password`, { password }),
};

export const auditApi = {
  /**
   * @param {{projectId?: string, milestoneId?: string, action?: string[], from?: string, to?: string, q?: string,
   *   limit?: number}} filters Optional filters.
   */
  list: (filters = {}) => request('GET', `/audit${queryString({
    ...filters, action: filters.action?.length ? filters.action.join(',') : undefined,
  })}`),
  /**
   * One page: the same filters plus offset (entries to skip). Resolves to {entries, total, offset, limit}.
   * @param {object} filters As for list, plus offset.
   */
  page: (filters = {}) => request('GET', `/audit/page${queryString({
    ...filters, action: filters.action?.length ? filters.action.join(',') : undefined,
  })}`),
};
