import {
  createContext, useCallback, useContext, useEffect, useMemo, useState,
} from 'react';
import PropTypes from 'prop-types';
import { projectsApi, timeApi } from '../services/api.js';
import { formatHours } from '../utils/format.js';

/** How often the running timer is re-checked, in case it was started or stopped in another tab. */
const SYNC_EVERY_MS = 30_000;
/** How long "Logged … on …" stays up after stopping. */
const NOTICE_MS = 8000;

const TimerContext = createContext(undefined);

/**
 * The signed-in user's running timer, shared by the timer bar and the milestone buttons that start it.
 * `logged` counts the time logged through it, so pages showing progress can reload when it changes, and `notice`
 * says what the last stop logged, for a few seconds.
 * @param {object} props Component props.
 * @param {JSX.Element} props.children The app.
 * @returns {JSX.Element} The provider.
 */
export function TimerProvider({ children }) {
  const [timer, setTimer] = useState();
  const [names, setNames] = useState({});
  const [logged, setLogged] = useState(0);
  const [notice, setNotice] = useState();

  const sync = useCallback(async () => {
    try {
      setTimer(await timeApi.current());
    } catch {
      // Keep showing what we had; the next sync tries again.
    }
  }, []);

  useEffect(() => {
    sync();
    const every = setInterval(sync, SYNC_EVERY_MS);
    return () => clearInterval(every);
  }, [sync]);

  // Name the milestone and project of a timer found on load, e.g. after a page reload.
  useEffect(() => {
    if (!timer || names[timer.milestoneId]) {
      return;
    }
    projectsApi.get(timer.projectId).then((project) => {
      const milestone = project.milestones.find((found) => found.id === timer.milestoneId);
      setNames((current) => ({
        ...current, [timer.milestoneId]: { project: project.name, milestone: milestone?.name ?? 'a milestone' },
      }));
    }).catch(() => {});
  }, [timer, names]);

  const start = useCallback(async (project, milestone) => {
    setNames((current) => ({ ...current, [milestone.id]: { project: project.name, milestone: milestone.name } }));
    setTimer(await timeApi.start(project.id, milestone.id));
  }, []);

  useEffect(() => {
    if (!notice) {
      return undefined;
    }
    const hide = setTimeout(() => setNotice(undefined), NOTICE_MS);
    return () => clearTimeout(hide);
  }, [notice]);

  const stop = useCallback(async () => {
    const stopped = timer;
    const entry = await timeApi.stop();
    const name = stopped ? names[stopped.milestoneId]?.milestone : undefined;
    setTimer(undefined);
    setLogged((count) => count + 1);
    setNotice({
      projectId: stopped?.projectId,
      milestoneId: stopped?.milestoneId,
      text: `Logged ${formatHours(Number(entry.hours))}${name ? ` on ${name}` : ''}.`,
    });
    return entry;
  }, [timer, names]);

  const recordLogged = useCallback(() => setLogged((count) => count + 1), []);

  const value = useMemo(() => ({
    timer, names: timer ? names[timer.milestoneId] : undefined, start, stop, sync, logged, recordLogged, notice,
  }), [timer, names, start, stop, sync, logged, recordLogged, notice]);

  return <TimerContext.Provider value={value}>{children}</TimerContext.Provider>;
}

TimerProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

/**
 * @returns {{timer: object|undefined, names: {project: string, milestone: string}|undefined, start: Function,
 *   stop: Function, sync: Function, logged: number, recordLogged: Function,
 *   notice: {projectId: string, milestoneId: string, text: string}|undefined}} The running timer and its actions.
 */
export function useTimer() {
  const value = useContext(TimerContext);
  if (!value) {
    throw new Error('useTimer must be used inside TimerProvider');
  }
  return value;
}
