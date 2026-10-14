import { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { parseJson, sendRequest } from '../services/apiClient.js';
import { MAX_TIMER_MS, elapsedMs, formatDuration } from '../services/timer.js';

/** How often the panel re-checks the backend, in case the timer was started or stopped elsewhere. */
const SYNC_INTERVAL_MS = 30_000;

/**
 * The signed-in employee's running timer: elapsed time ticking every second, the time left before the
 * 8-hour cap, and a Stop button.
 * @param {object} props Component props.
 * @param {string} props.service Backend service name.
 * @param {string} props.accessToken Current access token, or '' when signed out.
 * @param {number} props.refreshKey Changes after every request sent from the tester, to re-check at once.
 * @param {Function} props.onResult Called with the raw result of stopping the timer, to show it.
 * @returns {JSX.Element|null} The timer panel, or nothing when signed out.
 */
function TimerPanel({
  service, accessToken, refreshKey, onResult,
}) {
  const [state, setState] = useState({ kind: 'loading' });
  const [now, setNow] = useState(Date.now());
  const [stopping, setStopping] = useState(false);

  const load = useCallback(async () => {
    if (!accessToken) {
      setState({ kind: 'signed-out' });
      return;
    }
    const result = await sendRequest({
      service, method: 'GET', path: '/timer', body: '', accessToken,
    });
    if (result.ok) {
      setState({ kind: 'running', timer: parseJson(result.body) });
    } else if (result.status === 404) {
      setState({ kind: 'idle' });
    } else if (result.status === 403) {
      setState({ kind: 'not-employee' });
    } else {
      setState({ kind: 'error', message: result.error || parseJson(result.body)?.error || result.statusText });
    }
  }, [service, accessToken]);

  useEffect(() => {
    load();
    const sync = setInterval(load, SYNC_INTERVAL_MS);
    return () => clearInterval(sync);
  }, [load, refreshKey]);

  const running = state.kind === 'running';
  const elapsed = running ? elapsedMs(state.timer.startedAt, now) : 0;
  const capped = running && elapsed >= MAX_TIMER_MS;

  // Tick once a second while a timer runs; at the cap, ask the backend, which closes the timer at 8 hours.
  useEffect(() => {
    if (!running) {
      return undefined;
    }
    if (capped) {
      load();
      return undefined;
    }
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(tick);
  }, [running, capped, load]);

  const stop = async () => {
    setStopping(true);
    const result = await sendRequest({
      service, method: 'POST', path: '/timer/stop', body: '', accessToken,
    });
    setStopping(false);
    onResult(result);
    load();
  };

  if (state.kind === 'signed-out' || state.kind === 'not-employee' || state.kind === 'loading') {
    return null;
  }

  return (
    <section className="timer-panel" aria-live="polite">
      <h2>Timer</h2>
      {state.kind === 'idle' && <p className="muted">No timer running. Start one with the Start timer preset.</p>}
      {state.kind === 'error' && <p className="fail">{`Could not load the timer: ${state.message}`}</p>}
      {running && (
        <div className="row timer-row">
          <div className="grow">
            <p className="timer-clock" aria-label="Elapsed time">{formatDuration(elapsed)}</p>
            <p className="muted">
              {`Milestone ${state.timer.milestoneId}`}
              {state.timer.note ? ` · ${state.timer.note}` : ''}
            </p>
            <p className="muted">
              {capped
                ? 'Reached the 8-hour limit; it has been logged as 8 hours.'
                : `Stops counting in ${formatDuration(MAX_TIMER_MS - elapsed)}`}
            </p>
          </div>
          <button type="button" onClick={stop} disabled={stopping}>{stopping ? 'Stopping…' : 'Stop timer'}</button>
        </div>
      )}
    </section>
  );
}

TimerPanel.propTypes = {
  service: PropTypes.string.isRequired,
  accessToken: PropTypes.string.isRequired,
  refreshKey: PropTypes.number.isRequired,
  onResult: PropTypes.func.isRequired,
};

export default TimerPanel;
