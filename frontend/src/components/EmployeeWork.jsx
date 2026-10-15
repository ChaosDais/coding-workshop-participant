import { useCallback } from 'react';
import PropTypes from 'prop-types';
import ErrorMessage from './ErrorMessage.jsx';
import Loading from './Loading.jsx';
import StatusBadge from './StatusBadge.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { timeApi } from '../services/api.js';
import { formatHours, formatTimestamp } from '../utils/format.js';

/**
 * A person's work, shown when their row is opened on the Employees page: the projects and milestones they are
 * assigned to and the time they have logged on each, on current projects: completed and archived projects are
 * left out. Milestones they logged time on but have left, or that were archived, are listed too. Managers see
 * only their own projects.
 * @param {object} props Component props.
 * @param {{id: string, name: string}} props.person The employee.
 * @param {Map<string, number>} [props.shares] Their share of each unfinished milestone's expected hours, by
 *   milestone id, from workloadByEmployee; shown as a Workload column when given.
 * @returns {JSX.Element} The breakdown.
 */
function EmployeeWork({ person, shares }) {
  const load = useCallback(() => timeApi.forUser(person.id), [person.id]);
  const { data, error, loading, reload } = useLoader(load);

  if (error) {
    return <ErrorMessage error={error} onRetry={reload} />;
  }
  if (loading && !data) {
    return <Loading>{`Loading ${person.name}'s work…`}</Loading>;
  }
  const projects = data.projects.filter((project) => !project.archived && project.status !== 'COMPLETE');
  if (projects.length === 0) {
    return <p className="muted">{`${person.name} has no work on current projects.`}</p>;
  }
  const total = projects.reduce((sum, project) => sum + Number(project.hours), 0);
  return (
    <div className="employee-work">
      <p className="employee-work-total">
        <span className="muted">Logged on current projects: </span>
        <strong>{formatHours(total)}</strong>
      </p>
      {projects.map((project) => (
        <section key={project.projectId} className="employee-work-project" aria-label={project.projectName}>
          <div className="employee-work-heading">
            {project.archived ? <strong>{project.projectName}</strong> : <a href={`#/projects/${project.projectId}`}>{project.projectName}</a>}
            <StatusBadge status={project.status} />
            {project.archived && <span className="badge archived-badge">Archived</span>}
            <span className="employee-work-hours">{formatHours(project.hours)}</span>
          </div>
          <table className="compact">
            <thead>
              <tr>
                <th scope="col">Milestone</th>
                <th scope="col">Status</th>
                <th scope="col">Assigned</th>
                {shares && <th scope="col" className="numeric">Workload share</th>}
                <th scope="col" className="numeric">Time logged</th>
                <th scope="col">Last logged</th>
              </tr>
            </thead>
            <tbody>
              {project.milestones.map((milestone) => (
                <tr key={milestone.milestoneId} className={milestone.archived ? 'archived' : undefined}>
                  <td>
                    {milestone.milestoneName}
                    {milestone.archived && <span className="badge archived-badge">Archived</span>}
                  </td>
                  <td><StatusBadge status={milestone.status} /></td>
                  <td>{milestone.assigned ? 'Yes' : <span className="muted">No longer</span>}</td>
                  {shares && (
                    <td className="numeric nowrap">
                      {shares.has(milestone.milestoneId) ? formatHours(shares.get(milestone.milestoneId)) : <span className="muted">—</span>}
                    </td>
                  )}
                  <td className="numeric nowrap">
                    {formatHours(milestone.hours)}
                    {milestone.entries > 0 && <span className="muted small">{` · ${milestone.entries} ${milestone.entries === 1 ? 'entry' : 'entries'}`}</span>}
                  </td>
                  <td className="nowrap">{milestone.lastLoggedAt ? formatTimestamp(milestone.lastLoggedAt) : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  );
}

EmployeeWork.propTypes = {
  person: PropTypes.shape({ id: PropTypes.string.isRequired, name: PropTypes.string.isRequired }).isRequired,
  shares: PropTypes.instanceOf(Map),
};

export default EmployeeWork;
