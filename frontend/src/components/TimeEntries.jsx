import { useCallback } from 'react';
import PropTypes from 'prop-types';
import ErrorMessage from './ErrorMessage.jsx';
import Loading from './Loading.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { timeApi } from '../services/api.js';
import { formatHours, formatMoney, formatTimestamp } from '../utils/format.js';

/**
 * Time logged on a milestone, newest last. Employees see their own entries; managers and admins see everyone's,
 * and admins also see the cost.
 * @param {object} props Component props.
 * @param {string} props.projectId Project id.
 * @param {string} props.milestoneId Milestone id.
 * @param {Map<string, {name: string}>} props.usersById Names for the people who logged time.
 * @returns {JSX.Element} The entries.
 */
function TimeEntries({ projectId, milestoneId, usersById }) {
  const load = useCallback(() => timeApi.list(projectId, milestoneId), [projectId, milestoneId]);
  const { data, error, loading, reload } = useLoader(load);

  if (error) {
    return <ErrorMessage error={error} onRetry={reload} />;
  }
  if (loading && !data) {
    return <Loading>Loading time…</Loading>;
  }
  if (data.length === 0) {
    return <p className="muted">No time logged yet.</p>;
  }
  const showCost = data.some((entry) => entry.cost !== undefined);
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Who</th>
            <th scope="col" className="numeric">Hours</th>
            <th scope="col">How</th>
            <th scope="col">Note</th>
            {showCost && <th scope="col" className="numeric">Cost</th>}
          </tr>
        </thead>
        <tbody>
          {data.map((entry) => (
            <tr key={entry.id}>
              <td className="nowrap">{formatTimestamp(entry.startedAt ?? entry.loggedAt)}</td>
              <td>{usersById.get(entry.userId)?.name ?? 'Someone else'}</td>
              <td className="numeric">{entry.hours === undefined ? 'Running' : formatHours(Number(entry.hours))}</td>
              <td>{entry.source === 'TIMER' ? 'Timer' : 'Entered'}</td>
              <td>
                {entry.note ?? <span className="muted">—</span>}
                {entry.correctedAt && <p className="muted small">{`Corrected by ${entry.correctedBy}: ${entry.correctionReason}`}</p>}
              </td>
              {showCost && <td className="numeric">{formatMoney(entry.cost)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

TimeEntries.propTypes = {
  projectId: PropTypes.string.isRequired,
  milestoneId: PropTypes.string.isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
};

export default TimeEntries;
