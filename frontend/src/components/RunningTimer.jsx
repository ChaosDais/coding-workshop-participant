import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import ErrorMessage from './ErrorMessage.jsx';
import { useTimer } from '../timer/TimerContext.jsx';
import { MAX_TIMER_MS, elapsedMs, formatDuration } from '../services/timer.js';

/**
 * The running timer: the time so far, ticking every second, the time left before the 8-hour cap, and Stop.
 * Used in the timer bar and on the milestone the timer runs on.
 * @param {object} props Component props.
 * @param {boolean} [props.showWhat] Whether to name the milestone and project, when shown away from them.
 * @returns {JSX.Element|null} The timer, or nothing when none runs.
 */
function RunningTimer({ showWhat = false }) {
  const {
    timer, names, stop, sync,
  } = useTimer();
  const [now, setNow] = useState(Date.now());
  const [stopping, setStopping] = useState(false);
  const [error, setError] = useState();

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

  const onStop = async () => {
    setStopping(true);
    setError(undefined);
    try {
      await stop();
    } catch (failure) {
      setError(failure);
    } finally {
      setStopping(false);
    }
  };

  if (!timer) {
    return null;
  }
  return (
    <>
      <span className="timer-clock" aria-label="Time on the timer">{formatDuration(elapsed)}</span>
      {showWhat && (
        <span className="timer-what">
          {names ? `${names.milestone} · ${names.project}` : 'Timer running'}
          {timer.note && <span className="muted">{` · ${timer.note}`}</span>}
        </span>
      )}
      <span className="muted small">
        {capped ? 'Reached the 8-hour limit' : `Stops counting in ${formatDuration(MAX_TIMER_MS - elapsed)}`}
      </span>
      <button type="button" className="primary" onClick={onStop} disabled={stopping}>
        {stopping ? 'Stopping…' : 'Stop Timer'}
      </button>
      <ErrorMessage error={error} />
    </>
  );
}

RunningTimer.propTypes = {
  showWhat: PropTypes.bool,
};

export default RunningTimer;
