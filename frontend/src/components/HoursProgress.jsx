import PropTypes from 'prop-types';
import { formatHours } from '../utils/format.js';
import { hoursAgainstExpected } from '../utils/projects.js';

/**
 * Logged hours against the expected hours: "12.5 h of 40 h", a completion bar and percentage, and, for admins and
 * managers, a warning once progress passes the estimate.
 * @param {object} props Component props.
 * @param {number} props.progress Hours logged.
 * @param {number} [props.expected] Expected hours; without it only the logged hours show.
 * @param {boolean} [props.showOverrun] Whether to flag progress beyond the estimate (admins and managers only).
 * @param {string} props.label What is measured, for screen readers, e.g. "Apollo".
 * @returns {JSX.Element} The progress display.
 */
function HoursProgress({
  progress, expected, showOverrun = false, label,
}) {
  const { expected: hasEstimate, over, overBy, percent } = hoursAgainstExpected(progress, expected);
  if (!hasEstimate) {
    return <span className="hours">{formatHours(progress)}</span>;
  }
  return (
    <span className={`hours${over && showOverrun ? ' over' : ''}`}>
      <span className="nowrap">{`${formatHours(progress)} of ${formatHours(expected)}`}</span>
      {percent !== undefined && (
        <span className="hours-bar-row">
          <span
            className="hours-bar"
            role="progressbar"
            aria-label={`${label} completion`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.min(percent, 100)}
            aria-valuetext={`${percent}% of expected hours`}
          >
            <span className="hours-bar-fill" style={{ width: `${Math.min(percent, 100)}%` }} />
          </span>
          <span className="hours-percent">{`${percent}%`}</span>
        </span>
      )}
      {over && showOverrun && <span className="badge over-badge">{`Over by ${formatHours(overBy)}`}</span>}
    </span>
  );
}

HoursProgress.propTypes = {
  progress: PropTypes.number.isRequired,
  expected: PropTypes.number,
  showOverrun: PropTypes.bool,
  label: PropTypes.string.isRequired,
};

export default HoursProgress;
