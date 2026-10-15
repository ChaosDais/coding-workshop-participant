import { useEffect, useState } from 'react';

/**
 * Like useState, but remembered for the rest of the browser session (sessionStorage), so a page keeps its
 * settings when the user goes elsewhere and comes back. Falls back to plain state when storage is unavailable.
 * @param {string} key Storage key, e.g. "projects.filters".
 * @param {*} initial The value when nothing is remembered; JSON-serialisable.
 * @returns {[*, Function]} The value and its setter, as useState returns.
 */
export function useSessionState(key, initial) {
  const [value, setValue] = useState(() => {
    try {
      const saved = sessionStorage.getItem(`acme.${key}`);
      return saved === null ? initial : JSON.parse(saved);
    } catch {
      return initial;
    }
  });
  useEffect(() => {
    try {
      sessionStorage.setItem(`acme.${key}`, JSON.stringify(value));
    } catch {
      // Storage unavailable (private mode, full): the value simply is not remembered.
    }
  }, [key, value]);
  return [value, setValue];
}
