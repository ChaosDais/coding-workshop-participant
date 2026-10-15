import {
  Fragment, useCallback, useMemo, useState,
} from 'react';
import ErrorMessage from '../components/ErrorMessage.jsx';
import Loading from '../components/Loading.jsx';
import HoursProgress from '../components/HoursProgress.jsx';
import Modal from '../components/Modal.jsx';
import ProjectForm from '../components/ProjectForm.jsx';
import ProjectPreview from '../components/ProjectPreview.jsx';
import { ChevronIcon, MagnifierIcon } from '../components/Icons.jsx';
import StatusAge from '../components/StatusAge.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import Workload from '../components/Workload.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { useToggleSet } from '../hooks/useToggleSet.js';
import { navigate } from '../hooks/useHashRoute.js';
import { projectsApi } from '../services/api.js';
import { loadProjectsAndPeople } from '../services/projectData.js';
import { availability, projectRows, workloadByEmployee } from '../utils/projects.js';
import {
  STATUSES, formatDate, formatHours, formatTimestamp, statusLabel,
} from '../utils/format.js';
import { byId } from '../utils/users.js';

const NO_FILTERS = {
  search: '', status: '', managerId: '', showArchived: false, showCompleted: false,
};

/**
 * The home page: projects with their dates, status and how long they have been in it (flagging possible
 * bottlenecks), progress against expected hours and whether they can be worked on. Admins see every project and
 * can create them; managers see the projects they manage; employees see the unfinished projects they are assigned
 * to, with their own milestones and without the overrun and bottleneck warnings.
 * Open projects come first; completed ones are hidden unless asked for, and then shown greyed out at the bottom
 * with their completion dates. Clicking a row opens it in place to show its description and milestones; the
 * magnifier opens the full project view. Admins can archive projects and milestones from the list.
 * @returns {JSX.Element} The projects page.
 */
function ProjectsPage() {
  const { user } = useAuth();
  const isAdmin = user.role === 'ADMIN';
  const isEmployee = user.role === 'EMPLOYEE';
  const load = useCallback(() => loadProjectsAndPeople(user), [user]);
  const { data, error, loading, reload } = useLoader(load);
  const [filters, setFilters] = useState(NO_FILTERS);
  const [creating, setCreating] = useState(false);
  const [expanded, toggle] = useToggleSet();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState();

  const act = async (action) => {
    setActionError(undefined);
    setBusy(true);
    try {
      await action();
      reload();
    } catch (failure) {
      setActionError(failure);
    } finally {
      setBusy(false);
    }
  };
  const archiveProject = (project) => window.confirm(`Archive ${project.name}? It can be restored later.`)
    && act(() => projectsApi.archive(project.id));
  const archiveMilestone = (project, milestone) => window.confirm(`Archive milestone ${milestone.name} of ${project.name}?`)
    && act(() => projectsApi.archiveMilestone(project.id, milestone.id));

  const lookups = useMemo(() => {
    if (!data) {
      return undefined;
    }
    return {
      users: byId(data.users),
      projects: new Map([...data.active, ...data.archived].map((project) => [project.id, project])),
      managers: data.users.filter((person) => person.role === 'MANAGER' && !person.archived),
      // Managers of any loaded project, archived or not, so filtering by an archived manager still works.
      projectManagers: [...new Set([...data.active, ...data.archived].map((project) => project.manager))]
        .map((id) => data.users.find((person) => person.id === id) ?? { id, name: 'Unknown manager' })
        .sort((a, b) => a.name.localeCompare(b.name)),
    };
  }, [data]);

  const rows = data ? projectRows(data.active, data.archived, filters, lookups.users) : [];
  const filtered = Boolean(filters.search || filters.status || filters.managerId);
  const showingCompleted = filters.showCompleted || filters.status === 'COMPLETE';
  // Project, Status, Start, Expected end, Expected, Progress and Availability, plus the role's own columns.
  const columns = 7 + [isEmployee, isAdmin, showingCompleted, isAdmin].filter(Boolean).length;

  const set = (field) => (event) => {
    const value = event.target.type === 'checkbox' ? event.target.checked : event.target.value;
    setFilters((current) => ({ ...current, [field]: value }));
  };

  const create = async (body) => {
    const created = await projectsApi.create(body);
    setCreating(false);
    navigate(`/projects/${created.id}`);
  };

  return (
    <main className="page">
      <div className="page-header">
        <h1>{isAdmin ? 'Projects' : 'My Projects'}</h1>
        {isAdmin && <button type="button" className="primary" onClick={() => setCreating(true)} disabled={!data}>New Project</button>}
      </div>

      {isEmployee && data && (
        <p className="your-workload">
          <span className="muted">Your workload: </span>
          <Workload load={workloadByEmployee(data.active).get(user.id)} />
        </p>
      )}

      <div className="filters">
        <label htmlFor="projects-search">
          Search
          <input id="projects-search" type="search" placeholder={isAdmin ? 'Project, description or manager' : 'Project or description'} value={filters.search} onChange={set('search')} />
        </label>
        <label htmlFor="projects-status">
          Status
          <select id="projects-status" value={filters.status} onChange={set('status')}>
            <option value="">All statuses</option>
            {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
          </select>
        </label>
        {isAdmin && (
          <label htmlFor="projects-manager">
            Manager
            <select id="projects-manager" value={filters.managerId} onChange={set('managerId')} disabled={!data}>
              <option value="">All managers</option>
              {lookups?.projectManagers.map((manager) => <option key={manager.id} value={manager.id}>{manager.name}</option>)}
            </select>
          </label>
        )}
        {!isEmployee && (
          <label className="checkbox">
            <input type="checkbox" checked={filters.showCompleted} onChange={set('showCompleted')} />
            Show completed projects
          </label>
        )}
        {isAdmin && (
          <label className="checkbox">
            <input type="checkbox" checked={filters.showArchived} onChange={set('showArchived')} />
            Show archived projects
          </label>
        )}
        {filtered && <button type="button" onClick={() => setFilters((current) => ({ ...current, search: '', status: '', managerId: '' }))}>Clear filters</button>}
      </div>

      <ErrorMessage error={error} onRetry={reload} />
      <ErrorMessage error={actionError} />
      {loading && !data && <Loading>Loading projects…</Loading>}

      {data && data.active.length === 0 && data.archived.length === 0 && (
        <p className="muted">
          {isAdmin && 'No projects yet. Create the first one with New Project.'}
          {user.role === 'MANAGER' && 'You are not managing any projects yet.'}
          {isEmployee && 'You are not assigned to any current projects.'}
        </p>
      )}
      {data && (data.active.length > 0 || data.archived.length > 0) && (
        rows.length === 0 ? (
          <p className="muted">
            {filtered ? 'No projects match these filters.' : 'No open projects. Tick Show completed projects to see finished ones.'}
          </p>
        ) : (
          <div className="table-wrap">
            <table className="clickable">
              <thead>
                <tr>
                  <th scope="col">Project</th>
                  <th scope="col">Status</th>
                  {isEmployee && <th scope="col">My Milestones</th>}
                  {isAdmin && <th scope="col">Manager</th>}
                  <th scope="col">Start</th>
                  <th scope="col">Expected end</th>
                  {showingCompleted && <th scope="col">Completed</th>}
                  <th scope="col" className="numeric">Expected</th>
                  <th scope="col" className="numeric">Progress</th>
                  <th scope="col">Availability</th>
                  {isAdmin && <th scope="col"><span className="visually-hidden">Actions</span></th>}
                </tr>
              </thead>
              <tbody>
                {rows.map(({ project, archived, completed }) => {
                  const { state, label } = availability(project, lookups.projects);
                  const open = expanded.has(project.id);
                  const classes = [archived && 'archived', completed && 'completed', open && 'expanded'].filter(Boolean).join(' ');
                  return (
                    <Fragment key={project.id}>
                      <tr className={classes || undefined} onClick={() => toggle(project.id)}>
                        <td>
                          <span className="project-name-cell">
                            <button
                              type="button"
                              className="expand-toggle"
                              aria-expanded={open}
                              aria-controls={`project-preview-${project.id}`}
                              onClick={(event) => { event.stopPropagation(); toggle(project.id); }}
                            >
                              <ChevronIcon />
                              <span>{project.name}</span>
                            </button>
                            <a
                              href={`#/projects/${project.id}`}
                              className="icon-link"
                              aria-label={`Open ${project.name}`}
                              title="Open the full project view"
                              onClick={(event) => event.stopPropagation()}
                            >
                              <MagnifierIcon />
                            </a>
                            {archived && <span className="badge archived-badge">Archived</span>}
                          </span>
                        </td>
                        <td>
                          <StatusBadge status={project.status} />
                          {/* The days in the status show when the project is opened; a warning shows here too. */}
                          {!isEmployee && <StatusAge item={project} warningOnly />}
                        </td>
                        {isEmployee && (
                          <td>
                            <ul className="plain-list">
                              {project.milestones.filter((milestone) => milestone.assignedEmployees.includes(user.id))
                                .map((milestone) => (
                                  <li key={milestone.id}>
                                    {`${milestone.name} `}
                                    <StatusBadge status={milestone.status} />
                                  </li>
                                ))}
                            </ul>
                          </td>
                        )}
                        {isAdmin && <td>{lookups.users.get(project.manager)?.name ?? 'Unknown manager'}</td>}
                        <td className="nowrap">{formatDate(project.startDate)}</td>
                        <td className="nowrap">{formatDate(project.endDate)}</td>
                        {showingCompleted && <td className="nowrap">{project.completedAt ? formatTimestamp(project.completedAt) : '—'}</td>}
                        <td className="numeric nowrap">{project.expectedHours == null ? '—' : formatHours(project.expectedHours)}</td>
                        <td className="numeric">
                          <HoursProgress progress={project.progressTime} expected={project.expectedHours} showOverrun={!isEmployee} label={project.name} />
                        </td>
                        <td><span className={`availability ${state}`}>{label}</span></td>
                        {isAdmin && (
                          <td className="row-actions">
                            {!archived && (
                              <button
                                type="button"
                                className="link-button danger"
                                disabled={busy}
                                aria-label={`Archive ${project.name}`}
                                onClick={(event) => { event.stopPropagation(); archiveProject(project); }}
                              >
                                Archive
                              </button>
                            )}
                          </td>
                        )}
                      </tr>
                      {open && (
                        <tr className="preview-row" id={`project-preview-${project.id}`}>
                          <td colSpan={columns}>
                            <ProjectPreview
                              project={project}
                              usersById={lookups.users}
                              showOverrun={!isEmployee}
                            showStatusAge={!isEmployee}
                              highlightUserId={isEmployee ? user.id : undefined}
                            everyonesHours={!isEmployee}
                              onArchiveMilestone={isAdmin && !archived ? (milestone) => archiveMilestone(project, milestone) : undefined}
                              busy={busy}
                            />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )
      )}

      {creating && (
        <Modal title="New Project" onClose={() => setCreating(false)}>
          <ProjectForm managers={lookups.managers} projects={data.active} onSubmit={create} onCancel={() => setCreating(false)} />
        </Modal>
      )}
    </main>
  );
}

export default ProjectsPage;
