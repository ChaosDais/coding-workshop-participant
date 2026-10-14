import { useCallback, useEffect, useState } from 'react';

/**
 * Runs an async loader and tracks its result; reload() runs it again.
 * @param {Function} load Returns a promise of the data; re-run when it changes, so wrap it in useCallback.
 * @returns {{data: *, error: Error|undefined, loading: boolean, reload: Function}} The loader state.
 */
export function useLoader(load) {
  const [state, setState] = useState({ data: undefined, error: undefined, loading: true });

  const run = useCallback(async () => {
    setState((current) => ({ ...current, loading: true, error: undefined }));
    try {
      const data = await load();
      setState({ data, error: undefined, loading: false });
    } catch (error) {
      setState((current) => ({ ...current, error, loading: false }));
    }
  }, [load]);

  useEffect(() => {
    run();
  }, [run]);

  return { ...state, reload: run };
}
