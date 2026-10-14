import { useEffect, useState } from 'react';
import ErrorMessage from './ErrorMessage.jsx';
import { useTimer } from '../timer/TimerContext.jsx';
import { MAX_TIMER_MS, elapsedMs, formatDuration } from '../services/timer.js';
import { formatHours } from '../utils/format.js';

/**
 * The running timer, under the navigation bar on every page: what it is for, the time so far ticking every second,
 * the time left before the 8-hour cap, and Stop. Says what was logged once stopped.
 * @returns {JSX.Element|null} The bar, or nothing when no timer runs and nothing was just logged.
 */
function TimerBar() {
  const {
    timer, names, stop, sync,
  } = useTimer();
  const [now, setNow] = useState(Date.now());
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState();
  const [notice, setNotice] = useState();

  const elapsed = timer ? elapsedMs(timer.startedAt, now) : 0;
  const capped = Boolean(timer) && elapsed >= MAX_TIMER_MS;

  useEffect(() => {
    if (!timer) {
      return undefined;
    }
    if (capped) {
      // The backend closes a timer at 8 hours; checking makes it do so and log the time.
      sync();
      return undefined;
    }
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(tick);
  }, [timer, capped, sync]);

  useEffect(() => {
    if (!notice) {
      return undefined;
    }
    const hide = setTimeout(() => setNotice(undefined), 8000);
    return () => clearTimeout(hide);
  }, [notice]);

  const onStop = async () => {
    setStopping(true);
    setError(undefined);
    try {
      const entry = await stop();
      setNotice(`Logged ${formatHours(Number(entry.hours))}${names ? ` on ${names.milestone}` : ''}.`);
    } catch (failure) {
      setError(failure);
    } finally {
      setStopping(false);
    }
  };

  if (!timer && !notice && !error) {
    return null;
  }
  return (
    <div className="timer-bar" role="region" aria-label="Timer">
      {timer && (
        <>
          <span className="timer-clock" aria-label="Time on the timer">{formatDuration(elapsed)}</span>
          <span className="timer-what">
            {names ? `${names.milestone} · ${names.project}` : 'Timer running'}
            {timer.note && <span className="muted">{` · ${timer.note}`}</span>}
          </span>
          <span className="muted small">
            {capped ? 'Reached the 8-hour limit' : `Stops counting in ${formatDuration(MAX_TIMER_MS - elapsed)}`}
          </span>
          <button type="button" className="primary" onClick={onStop} disabled={stopping}>
            {stopping ? 'Stopping…' : 'Stop Timer'}
          </button>
        </>
      )}
      {!timer && notice && <span className="success" role="status">{notice}</span>}
      <ErrorMessage error={error} />
    </div>
  );
}

export default TimerBar;
