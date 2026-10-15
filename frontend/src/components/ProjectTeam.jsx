import PropTypes from 'prop-types';
import StatusBadge from './StatusBadge.jsx';
import { formatHours } from '../utils/format.js';

/**
 * Who is responsible for a project: its manager, then everyone assigned to its milestones and anyone else who
 * logged time on it, with their job, contact, milestones, hours logged here and share of the open milestones'
 * expected hours (each milestone's estimate split between its assignees). For the full project view.
 * @param {object} props Component props.
 * @param {{manager: string, milestones: object[], hoursByMilestone?: object}} props.project The project.
 * @param {Map<string, {name: string, email?: string, jobType?: string}>} props.usersById People by id.
 * @returns {JSX.Element} The team table.
 */
function ProjectTeam({ project, usersById }) {
  const hours = project.hoursByMilestone ?? {};
  const ids = [...new Set([
    ...project.milestones.flatMap((milestone) => milestone.assignedEmployees),
    ...Object.values(hours).flatMap((byUser) => Object.keys(byUser)),
  ])].filter((id) => id !== project.manager);

  const row = (id, isManager) => {
    const person = usersById.get(id);
    const milestones = project.milestones.filter((milestone) => milestone.assignedEmployees.includes(id));
    const logged = Object.values(hours).reduce((sum, byUser) => sum + Number(byUser[id] ?? 0), 0);
    const share = milestones
      .filter((milestone) => milestone.status !== 'COMPLETE' && milestone.expectedHours)
      .reduce((sum, milestone) => sum + milestone.expectedHours / milestone.assignedEmployees.length, 0);
    return {
      id, isManager, person, milestones, logged, share,
      name: person?.name ?? (isManager ? 'Unknown manager' : 'Former team member'),
    };
  };
  const rows = [
    row(project.manager, true),
    ...ids.map((id) => row(id, false)).sort((a, b) => a.name.localeCompare(b.name)),
  ];
  const people = rows.length - 1;

  return (
    <div className="table-wrap">
      <table aria-label="Project team">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Job</th>
            <th scope="col">Email</th>
            <th scope="col">Milestones</th>
            <th scope="col" className="numeric">Logged here</th>
            <th scope="col" className="numeric">Share of expected</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({
            id, isManager, person, milestones, logged, share, name,
          }) => (
            <tr key={id}>
              <td>
                {name}
                {isManager && <span className="badge manager-badge">Manager</span>}
              </td>
              <td>{person?.jobType ?? <span className="muted">—</span>}</td>
              <td>{person?.email ?? <span className="muted">—</span>}</td>
              <td>
                {milestones.length > 0 ? (
                  <ul className="plain-list">
                    {milestones.map((milestone) => (
                      <li key={milestone.id}>
                        {`${milestone.name} `}
                        <StatusBadge status={milestone.status} />
                      </li>
                    ))}
                  </ul>
                ) : <span className="muted">{isManager ? 'Manages the project' : 'No longer assigned'}</span>}
              </td>
              <td className="numeric nowrap">{logged > 0 ? formatHours(logged) : <span className="muted">—</span>}</td>
              <td className="numeric nowrap">{share > 0 ? formatHours(share) : <span className="muted">—</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {people === 0 && <p className="muted small">Nobody is assigned to this project&apos;s milestones yet.</p>}
    </div>
  );
}

ProjectTeam.propTypes = {
  project: PropTypes.shape({
    manager: PropTypes.string.isRequired,
    milestones: PropTypes.arrayOf(PropTypes.shape({
      id: PropTypes.string.isRequired,
      name: PropTypes.string.isRequired,
      status: PropTypes.string.isRequired,
      expectedHours: PropTypes.number,
      assignedEmployees: PropTypes.arrayOf(PropTypes.string).isRequired,
    })).isRequired,
    hoursByMilestone: PropTypes.object,
  }).isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
};

export default ProjectTeam;
