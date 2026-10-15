import PropTypes from 'prop-types';
import HoursProgress from './HoursProgress.jsx';
import StatusBadge from './StatusBadge.jsx';
import Workload from './Workload.jsx';
import { formatDate } from '../utils/format.js';

/**
 * A manager, opened on the Employees page: the current projects they manage (not completed or archived), each with its milestones and who is
 * assigned, and the team of employees reporting to them.
 * @param {object} props Component props.
 * @param {{id: string, name: string}} props.manager The manager.
 * @param {Map<string, {id: string, name: string, role: string, managerId?: string, archived?: boolean,
 *   email: string, jobType?: string}>} props.people Everyone, by id, to name assignees and find the team.
 * @param {object[]} props.projects Active projects; theirs are picked out.
 * @param {Map<string, object>} props.workloads Each employee's workload, from workloadByEmployee.
 * @returns {JSX.Element} The breakdown.
 */
function ManagerWork({
  manager, people, projects: allProjects, workloads,
}) {
  const projects = allProjects.filter((project) => project.manager === manager.id && project.status !== 'COMPLETE');
  const team = [...people.values()]
    .filter((person) => person.role === 'EMPLOYEE' && !person.archived && person.managerId === manager.id)
    .sort((a, b) => a.name.localeCompare(b.name));
  const name = (id) => people.get(id)?.name ?? 'Former team member';

  return (
    <div className="employee-work">
      <section aria-label={`Projects managed by ${manager.name}`}>
        <h3 className="preview-heading">{`Projects (${projects.length})`}</h3>
        {projects.length === 0 && <p className="muted">{`${manager.name} does not manage any current projects.`}</p>}
        {projects.map((project) => (
          <section key={project.id} className="employee-work-project" aria-label={project.name}>
            <div className="employee-work-heading">
              <a href={`#/projects/${project.id}`}>{project.name}</a>
              <StatusBadge status={project.status} />
              <span className="muted small nowrap">{`${formatDate(project.startDate)} – ${formatDate(project.endDate)}`}</span>
              <span className="employee-work-hours">
                <HoursProgress progress={project.progressTime} expected={project.expectedHours} showOverrun label={project.name} />
              </span>
            </div>
            {project.milestones.length === 0 ? <p className="muted employee-work-empty">No milestones yet.</p> : (
              <table className="compact">
                <thead>
                  <tr>
                    <th scope="col">Milestone</th>
                    <th scope="col">Status</th>
                    <th scope="col">Assigned</th>
                    <th scope="col" className="numeric">Progress</th>
                  </tr>
                </thead>
                <tbody>
                  {project.milestones.map((milestone) => (
                    <tr key={milestone.id}>
                      <td>{milestone.name}</td>
                      <td><StatusBadge status={milestone.status} /></td>
                      <td>
                        {milestone.assignedEmployees.length > 0 ? milestone.assignedEmployees.map(name).join(', ')
                          : <span className="muted">Nobody yet</span>}
                      </td>
                      <td className="numeric">
                        <HoursProgress progress={milestone.progressTime} expected={milestone.expectedHours} showOverrun label={milestone.name} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        ))}
      </section>

      <section aria-label={`Team of ${manager.name}`}>
        <h3 className="preview-heading">{`Team (${team.length})`}</h3>
        {team.length === 0 ? <p className="muted">{`No employees report to ${manager.name} yet.`}</p> : (
          <div className="employee-work-project">
            <table className="compact">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Email</th>
                  <th scope="col">Job</th>
                  <th scope="col">Workload</th>
                </tr>
              </thead>
              <tbody>
                {team.map((person) => (
                  <tr key={person.id}>
                    <td>{person.name}</td>
                    <td>{person.email}</td>
                    <td>{person.jobType ?? <span className="muted">—</span>}</td>
                    <td><Workload load={workloads.get(person.id)} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

ManagerWork.propTypes = {
  manager: PropTypes.shape({ id: PropTypes.string.isRequired, name: PropTypes.string.isRequired }).isRequired,
  people: PropTypes.instanceOf(Map).isRequired,
  projects: PropTypes.arrayOf(PropTypes.object).isRequired,
  workloads: PropTypes.instanceOf(Map).isRequired,
};

export default ManagerWork;
