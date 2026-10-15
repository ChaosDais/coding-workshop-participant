import {
  Fragment, useCallback, useMemo, useState,
} from 'react';
import EmployeeWork from '../components/EmployeeWork.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import { ChevronIcon } from '../components/Icons.jsx';
import Workload from '../components/Workload.jsx';
import Loading from '../components/Loading.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { useToggleSet } from '../hooks/useToggleSet.js';
import { projectsApi, usersApi } from '../services/api.js';
import { workloadByEmployee } from '../utils/projects.js';
import { filterUsers } from '../utils/users.js';

async function loadTeam() {
  const [team, projects] = await Promise.all([usersApi.list(false), projectsApi.list(false)]);
  return { team, projects };
}

/**
 * A manager's team: each employee's name, email, ID and job, and the milestones they are currently working on in
 * the manager's projects (completed milestones and projects are left out), and their workload: their share of
 * those milestones' expected hours. Opening a row shows everything they
 * are assigned to on those projects and the time they logged on each.
 * @returns {JSX.Element} The team page.
 */
function TeamPage() {
  const { data, error, loading, reload } = useLoader(useCallback(loadTeam, []));
  const [search, setSearch] = useState('');
  const [expanded, toggle] = useToggleSet();

  const assignments = useMemo(() => {
    const byEmployee = new Map();
    data?.projects
      .filter((project) => project.status !== 'COMPLETE')
      .forEach((project) => project.milestones
        .filter((milestone) => milestone.status !== 'COMPLETE')
        .forEach((milestone) => milestone.assignedEmployees.forEach((id) => {
          byEmployee.set(id, [...(byEmployee.get(id) ?? []), { project, milestone }]);
        })));
    return byEmployee;
  }, [data]);

  const workloads = useMemo(() => workloadByEmployee(data?.projects ?? []), [data]);
  const rows = data ? filterUsers(data.team, { search }) : [];

  return (
    <main className="page">
      <div className="page-header">
        <h1>My Team</h1>
      </div>
      <div className="filters">
        <label htmlFor="team-search">
          Search
          <input id="team-search" type="search" placeholder="Name, email, job or ID" value={search} onChange={(event) => setSearch(event.target.value)} />
        </label>
      </div>

      <ErrorMessage error={error} onRetry={reload} />
      {loading && !data && <Loading>Loading your team…</Loading>}
      {data && data.team.length === 0 && <p className="muted">No employees report to you yet. An admin assigns employees to managers.</p>}
      {data && data.team.length > 0 && (rows.length === 0 ? <p className="muted">Nobody matches this search.</p> : (
        <div className="table-wrap">
          <table className="clickable">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">ID</th>
                <th scope="col">Job</th>
                <th scope="col">Workload</th>
                <th scope="col">Working on</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((person) => {
                const work = assignments.get(person.id) ?? [];
                const open = expanded.has(person.id);
                return (
                  <Fragment key={person.id}>
                    <tr className={open ? 'expanded' : undefined} onClick={() => toggle(person.id)}>
                      <td>
                        <button
                          type="button"
                          className="expand-toggle"
                          aria-expanded={open}
                          aria-controls={`work-${person.id}`}
                          onClick={(event) => { event.stopPropagation(); toggle(person.id); }}
                        >
                          <ChevronIcon />
                          <span>{person.name}</span>
                        </button>
                      </td>
                      <td>{person.email}</td>
                      <td><code title={person.id}>{person.id.slice(0, 8)}</code></td>
                      <td>{person.jobType ?? <span className="muted">—</span>}</td>
                      <td><Workload load={workloads.get(person.id)} /></td>
                      <td>
                        {work.length === 0 ? <span className="muted">Not assigned</span> : (
                          <ul className="plain-list">
                            {work.map(({ project, milestone }) => (
                              <li key={`${project.id}-${milestone.id}`}>
                                <a href={`#/projects/${project.id}`} onClick={(event) => event.stopPropagation()}>{project.name}</a>
                                {` · ${milestone.name} `}
                                <StatusBadge status={milestone.status} />
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                    </tr>
                    {open && (
                      <tr className="preview-row" id={`work-${person.id}`}>
                        <td colSpan={6}><EmployeeWork person={person} shares={workloads.get(person.id)?.shares} /></td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      ))}
    </main>
  );
}

export default TeamPage;
