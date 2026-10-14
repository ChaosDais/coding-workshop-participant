import { useEffect, useState } from 'react';

/** The current hash path split into segments, e.g. "#/projects/abc" → ["projects", "abc"]. */
function currentSegments() {
  return window.location.hash.replace(/^#\/?/, '').split('?')[0].split('/').filter(Boolean)
    .map(decodeURIComponent);
}

/**
 * Hash-based routing, so the app works from S3 and CloudFront without server-side rewrites.
 * @returns {string[]} The current path segments.
 */
export function useHashRoute() {
  const [segments, setSegments] = useState(currentSegments);
  useEffect(() => {
    const update = () => setSegments(currentSegments());
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  return segments;
}

/** @param {string} path App path such as "/projects/abc". */
export function navigate(path) {
  window.location.hash = path;
}
