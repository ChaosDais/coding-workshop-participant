import { useCallback, useState } from 'react';

/**
 * Which items are open, e.g. expanded rows: a set of ids and a function that opens or closes one.
 * @returns {[Set<string>, Function]} The open ids, and toggle(id).
 */
export function useToggleSet() {
  const [open, setOpen] = useState(() => new Set());
  const toggle = useCallback((id) => setOpen((current) => {
    const next = new Set(current);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    return next;
  }), []);
  return [open, toggle];
}
