const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:3001';

/**
 * Names of the backend services the deploy scripts exposed to the frontend.
 * @returns {string[]} Service names, empty when none were generated.
 */
export function getServiceNames() {
  try {
    return Object.keys(JSON.parse(import.meta.env.VITE_API_ENDPOINTS || '{}'));
  } catch {
    return [];
  }
}

/**
 * Builds the full URL for a service endpoint.
 * @param {string} service Backend service name, e.g. "workshop-service".
 * @param {string} path Path below the service, e.g. "/projects".
 * @returns {string} Absolute request URL.
 */
export function buildUrl(service, path) {
  const suffix = path && !path.startsWith('/') ? `/${path}` : path;
  return `${API_URL}/api/${service}${suffix}`;
}

/**
 * Replaces {name} placeholders, such as {projectId} or {refreshToken}, with the matching values.
 * Placeholders without a value are left in place so the request visibly stays invalid.
 * @param {string} text Path or body template.
 * @param {Object<string, string>} values Values to substitute.
 * @returns {string} Text with placeholders filled in.
 */
export function fillPlaceholders(text, values) {
  return text.replace(/\{(\w+)\}/g, (placeholder, name) => values[name] || placeholder);
}

/**
 * Parses JSON text.
 * @param {string} text Raw response body.
 * @returns {*} The parsed value, or undefined when the text is not valid JSON.
 */
export function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/**
 * Sends one request and reports the outcome; network failures are returned, not thrown.
 * @param {{service: string, method: string, path: string, body: string, accessToken?: string}} request
 *   Request to send; an access token is sent as a bearer token.
 * @returns {Promise<{url: string, status: number, statusText: string, ok: boolean,
 *   durationMs: number, body: string, error?: string}>} Raw result.
 */
export async function sendRequest({
  service, method, path, body, accessToken,
}) {
  const url = buildUrl(service, path);
  const hasBody = Boolean(body && body.trim()) && !['GET', 'HEAD'].includes(method);
  const headers = {
    ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
    ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
  };
  const startedAt = performance.now();

  try {
    const response = await fetch(url, {
      method,
      headers,
      body: hasBody ? body : undefined,
    });
    return {
      url,
      status: response.status,
      statusText: response.statusText,
      ok: response.ok,
      durationMs: Math.round(performance.now() - startedAt),
      body: await response.text(),
    };
  } catch (error) {
    return {
      url,
      status: 0,
      statusText: '',
      ok: false,
      durationMs: Math.round(performance.now() - startedAt),
      body: '',
      error: error.message,
    };
  }
}
