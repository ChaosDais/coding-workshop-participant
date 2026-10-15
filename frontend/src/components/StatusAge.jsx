import PropTypes from 'prop-types';
import { statusLabel } from '../utils/format.js';
import { timeInStatus } from '../utils/projects.js';
import { useSettings } from '../settings/SettingsContext.jsx';

/**
 * "12 days in IN PROGRESS", with a warning and its reason when the time suggests a bottleneck.
 * Nothing shows for completed work or when the status-change time is unknown.
 * @param {object} props Component props.
 * @param {object} props.item A project or milestone.
 * @param {boolean} [props.warningOnly] Show only the warning, when there is one, and not the days.
 * @param {number} [props.now] Current time in milliseconds; defaults to now.
 * @returns {JSX.Element|null} The time in status.
 */
function StatusAge({ item, now = Date.now(), warningOnly = false }) {
  const { thresholds } = useSettings();
  const age = timeInStatus(item, now, thresholds);
  if (!age || (warningOnly && !age.notable)) {
    return null;
  }
  return (
    <span className={`status-age${age.notable ? ' notable' : ''}`}>
      {!warningOnly && <span className="nowrap">{`${age.days} ${age.days === 1 ? 'day' : 'days'} in ${statusLabel(item.status)}`}</span>}
      {age.notable && <span className="badge notable-badge">{`⚠ ${age.reason}`}</span>}
    </span>
  );
}

StatusAge.propTypes = {
  item: PropTypes.shape({
    status: PropTypes.string.isRequired,
    statusChangedAt: PropTypes.string,
    startDate: PropTypes.string,
    endDate: PropTypes.string,
  }).isRequired,
  now: PropTypes.number,
  warningOnly: PropTypes.bool,
};

export default StatusAge;
