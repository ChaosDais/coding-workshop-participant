import PropTypes from 'prop-types';
import RunningTimer from './RunningTimer.jsx';
import { useTimer } from '../timer/TimerContext.jsx';

/**
 * The running timer, under the navigation bar, and what it logged once stopped. Not shown on the full view of the
 * timer's own project, which shows the timer on its milestone instead.
 * @param {object} props Component props.
 * @param {string} [props.projectInView] The project whose full view is open, if any.
 * @returns {JSX.Element|null} The bar, or nothing.
 */
function TimerBar({ projectInView }) {
  const { timer, notice } = useTimer();
  const shown = timer ?? notice;
  if (!shown || shown.projectId === projectInView) {
    return null;
  }
  return (
    <div className="timer-bar" role="region" aria-label="Timer">
      {timer ? <RunningTimer showWhat /> : <span className="success" role="status">{notice.text}</span>}
    </div>
  );
}

TimerBar.propTypes = {
  projectInView: PropTypes.string,
};

export default TimerBar;
