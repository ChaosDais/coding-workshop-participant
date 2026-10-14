import PropTypes from 'prop-types';
import { actionLabel, describeDetails } from '../utils/audit.js';
import { formatTimestamp } from '../utils/format.js';

/**
 * Audit entries, newest first.
 * @param {object} props Component props.
 * @param {object[]} props.entries Audit entries.
 * @param {{users?: Map, milestones?: Map}} [props.names] Lookups to show names instead of ids.
 * @param {string} [props.empty] Message when there are no entries.
 * @returns {JSX.Element} The table.
 */
function AuditTable({ entries, names = {}, empty = 'No history yet.' }) {
  if (entries.length === 0) {
    return <p className="muted">{empty}</p>;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">What</th>
            <th scope="col">Who</th>
            <th scope="col">Details</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id}>
              <td className="nowrap">{formatTimestamp(entry.timestamp)}</td>
              <td>{actionLabel(entry.action)}</td>
              <td>{entry.actor}</td>
              <td>{describeDetails(entry, names)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

AuditTable.propTypes = {
  entries: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    action: PropTypes.string.isRequired,
    actor: PropTypes.string.isRequired,
    timestamp: PropTypes.string.isRequired,
    details: PropTypes.object,
  })).isRequired,
  names: PropTypes.shape({ users: PropTypes.instanceOf(Map), milestones: PropTypes.instanceOf(Map) }),
  empty: PropTypes.string,
};

export default AuditTable;
