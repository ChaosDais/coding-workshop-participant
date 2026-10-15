import PropTypes from 'prop-types';
import { formatHours } from '../utils/format.js';

/**
 * An employee's workload: "30 h across 3 milestones", noting milestones without an estimate.
 * @param {object} props Component props.
 * @param {{hours: number, milestones: number, unestimated: number}} [props.load] From workloadByEmployee; none
 *   when they have no unfinished milestones.
 * @returns {JSX.Element} The workload.
 */
function Workload({ load }) {
  if (!load) {
    return <span className="muted">None</span>;
  }
  return (
    <span className="workload">
      <strong className="nowrap">{formatHours(load.hours)}</strong>
      <span className="muted small nowrap">
        {` across ${load.milestones} ${load.milestones === 1 ? 'milestone' : 'milestones'}`}
        {load.unestimated > 0 && ` (${load.unestimated} without an estimate)`}
      </span>
    </span>
  );
}

Workload.propTypes = {
  load: PropTypes.shape({
    hours: PropTypes.number.isRequired,
    milestones: PropTypes.number.isRequired,
    unestimated: PropTypes.number.isRequired,
  }),
};

export default Workload;
