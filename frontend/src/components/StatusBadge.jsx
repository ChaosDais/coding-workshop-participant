import PropTypes from 'prop-types';
import { statusLabel } from '../utils/format.js';

/**
 * A coloured label for a completion status.
 * @param {object} props Component props.
 * @param {string} props.status e.g. "IN_PROGRESS".
 * @returns {JSX.Element} The badge.
 */
function StatusBadge({ status }) {
  return <span className={`badge status-${status.toLowerCase().replaceAll('_', '-')}`}>{statusLabel(status)}</span>;
}

StatusBadge.propTypes = {
  status: PropTypes.string.isRequired,
};

export default StatusBadge;
