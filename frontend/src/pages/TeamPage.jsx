import { useCallback, useMemo, useState } from 'react';
import ErrorMessage from '../components/ErrorMessage.jsx';
import Loading from '../components/Loading.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { projectsApi, usersApi } from '../services/api.js';
import { filterUsers } from '../utils/users.js';

async function loadTeam() {
  const [team, projects] = await Promise.all([usersApi.list(false), projectsApi.list(false)]);
  return { team, projects };
}

/**
 * A manager's team: each employee's name, email, ID and job, and the milestones they are currently working on in
 * the manager's projects (completed milestones and projects are left out).
 * @returns {JSX.Element} The team page.
 */
function TeamPage() {
  const { data, error, loading, reload } = useLoader(useCallback(loadTeam, []));
  const [search, setSearch] = useState('');

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
          <table>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">ID</th>
                <th scope="col">Job</th>
                <th scope="col">Working on</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((person) => {
                const work = assignments.get(person.id) ?? [];
                return (
                  <tr key={person.id}>
                    <td>{person.name}</td>
                    <td>{person.email}</td>
                    <td><code title={person.id}>{person.id.slice(0, 8)}</code></td>
                    <td>{person.jobType ?? <span className="muted">—</span>}</td>
                    <td>
                      {work.length === 0 ? <span className="muted">Not assigned</span> : (
                        <ul className="plain-list">
                          {work.map(({ project, milestone }) => (
                            <li key={`${project.id}-${milestone.id}`}>
                              <a href={`#/projects/${project.id}`}>{project.name}</a>
                              {` · ${milestone.name} `}
                              <StatusBadge status={milestone.status} />
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                  </tr>
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
