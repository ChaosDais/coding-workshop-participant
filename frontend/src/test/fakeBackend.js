import { vi } from 'vitest';

/**
 * Replaces fetch with a fake backend. Each route maps "METHOD /path" (query string included) to a response body,
 * or to a function of (body) returning {status, body}. Unknown routes answer 404, as the real router does.
 * @param {Object<string, *>} routes Responses by route.
 * @returns {Function} The fetch mock, to inspect calls.
 */
export function fakeBackend(routes) {
  const mock = vi.fn(async (url, options = {}) => {
    const { pathname, search } = new URL(url);
    const path = pathname.replace(/^\/api\/workshop-service/, '') || '/';
    const key = `${options.method ?? 'GET'} ${path}${search}`;
    const route = routes[key];
    let status = 200;
    let body = route;
    if (route === undefined) {
      status = 404;
      body = { error: `No route for ${key}` };
    } else if (typeof route === 'function') {
      ({ status, body } = route(options.body ? JSON.parse(options.body) : undefined));
    }
    return {
      ok: status >= 200 && status < 300,
      status,
      statusText: '',
      text: async () => (body === undefined ? '' : JSON.stringify(body)),
    };
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}
