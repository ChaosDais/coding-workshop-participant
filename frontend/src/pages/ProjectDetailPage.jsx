import { useCallback, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import AuditTable from '../components/AuditTable.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import MilestoneForm from '../components/MilestoneForm.jsx';
import Modal from '../components/Modal.jsx';
import ProjectForm from '../components/ProjectForm.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { auditApi, projectsApi } from '../services/api.js';
import { availability, blockingMilestones } from '../utils/projects.js';
import {
  STATUSES, formatDate, formatHours, formatMoney, formatTimestamp, statusLabel,
} from '../utils/format.js';
import { byId } from '../utils/users.js';
import { loadProjectsAndPeople } from '../services/adminData.js';

/**
 * One project in full: its details, dependencies, milestones with their assignees, and its history.
 * Admins can edit, archive and restore it, and add, edit, re-status and archive its milestones.
 * @param {object} props Component props.
 * @param {string} props.projectId The project's id.
 * @returns {JSX.Element} The project view.
 */
function ProjectDetailPage({ projectId }) {
  const load = useCallback(loadProjectsAndPeople, []);
  const { data, error, loading, reload } = useLoader(load);
  const [showArchivedMilestones, setShowArchivedMilestones] = useState(false);
  const [historyFor, setHistoryFor] = useState();
  const [editing, setEditing] = useState(false);
  const [milestoneForm, setMilestoneForm] = useState();
  const [actionError, setActionError] = useState();

  const loadArchived = useCallback(
    () => (showArchivedMilestones ? projectsApi.milestones(projectId, true) : Promise.resolve([])),
    [projectId, showArchivedMilestones],
  );
  const archivedMilestones = useLoader(loadArchived);

  const loadHistory = useCallback(
    () => auditApi.list(historyFor ? { milestoneId: historyFor.id } : { projectId }),
    [projectId, historyFor],
  );
  const history = useLoader(loadHistory);

  const lookups = useMemo(() => {
    if (!data) {
      return undefined;
    }
    const all = [...data.active, ...data.archived];
    return {
      users: byId(data.users),
      projects: new Map(all.map((project) => [project.id, project])),
      managers: data.users.filter((user) => user.role === 'MANAGER' && !user.archived),
    };
  }, [data]);

  const project = lookups?.projects.get(projectId);
  const archived = Boolean(data?.archived.some((found) => found.id === projectId));

  const refresh = () => {
    reload();
    archivedMilestones.reload();
    history.reload();
  };

  const act = async (action) => {
    setActionError(undefined);
    try {
      await action();
      refresh();
    } catch (failure) {
      setActionError(failure);
    }
  };

  if (error) {
    return <main className="page"><ErrorMessage error={error} onRetry={reload} /></main>;
  }
  if (loading && !data) {
    return <main className="page"><p className="muted" role="status">Loading project…</p></main>;
  }
  if (!project) {
    return (
      <main className="page">
        <p>This project does not exist.</p>
        <a href="#/projects">Back to projects</a>
      </main>
    );
  }

  const projectAvailability = availability(project, lookups.projects);
  const milestonesById = new Map([...project.milestones, ...(archivedMilestones.data ?? [])].map((m) => [m.id, m]));
  const team = data.users.filter((user) => user.role === 'EMPLOYEE' && !user.archived && user.managerId === project.manager);
  const names = (ids) => ids.map((id) => lookups.users.get(id)?.name ?? `Unknown (${id.slice(0, 8)})`).join(', ');

  const saveProject = async (body) => {
    await projectsApi.update(projectId, body);
    setEditing(false);
    refresh();
  };
  const saveMilestone = async (body) => {
    if (milestoneForm.milestone) {
      await projectsApi.updateMilestone(projectId, milestoneForm.milestone.id, body);
    } else {
      await projectsApi.addMilestone(projectId, body);
    }
    setMilestoneForm(undefined);
    refresh();
  };

  return (
    <main className="page">
      <a href="#/projects" className="back-link">← All Projects</a>
      <div className="page-header">
        <h1>
          {project.name}
          {' '}
          <StatusBadge status={project.status} />
          {archived && <span className="badge archived-badge">Archived</span>}
        </h1>
        <div className="toolbar">
          {!archived && <button type="button" onClick={() => setEditing(true)}>Edit Project</button>}
          {archived
            ? <button type="button" onClick={() => act(() => projectsApi.restore(projectId))}>Restore Project</button>
            : (
              <button type="button" className="danger" onClick={() => window.confirm(`Archive ${project.name}? It can be restored later.`) && act(() => projectsApi.archive(projectId))}>
                Archive Project
              </button>
            )}
        </div>
      </div>
      <ErrorMessage error={actionError} />

      <dl className="summary">
        <div><dt>Manager</dt><dd>{lookups.users.get(project.manager)?.name ?? project.manager}</dd></div>
        <div><dt>Dates</dt><dd>{`${formatDate(project.startDate)} – ${formatDate(project.endDate)}`}</dd></div>
        <div><dt>Progress</dt><dd>{formatHours(project.progressTime)}</dd></div>
        <div><dt>Budget</dt><dd>{formatMoney(project.budget)}</dd></div>
        <div><dt>Spent</dt><dd>{formatMoney(project.spent)}</dd></div>
        <div><dt>Completed</dt><dd>{project.completedAt ? formatTimestamp(project.completedAt) : '—'}</dd></div>
        <div>
          <dt>Availability</dt>
          <dd>{projectAvailability.label}</dd>
        </div>
      </dl>

      <section>
        <h2>Description</h2>
        <p className={project.description ? undefined : 'muted'}>{project.description ?? 'No description.'}</p>
      </section>

      <section>
        <h2>Depends on</h2>
        {project.dependsOn.length === 0 ? <p className="muted">No project dependencies.</p> : (
          <ul className="plain-list">
            {project.dependsOn.map((id) => {
              const dependency = lookups.projects.get(id);
              return (
                <li key={id}>
                  {dependency ? <a href={`#/projects/${id}`}>{dependency.name}</a> : `Unknown project (${id.slice(0, 8)})`}
                  {' '}
                  {dependency && <StatusBadge status={dependency.status} />}
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section>
        <div className="section-header">
          <h2>Milestones</h2>
          <div className="toolbar">
            <label className="checkbox">
              <input type="checkbox" checked={showArchivedMilestones} onChange={(event) => setShowArchivedMilestones(event.target.checked)} />
              Show archived milestones
            </label>
            {!archived && <button type="button" className="primary" onClick={() => setMilestoneForm({})}>Add milestone</button>}
          </div>
        </div>
        {project.milestones.length === 0 ? <p className="muted">No milestones yet.</p> : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Milestone</th>
                  <th scope="col">Status</th>
                  <th scope="col">Dates</th>
                  <th scope="col" className="numeric">Progress</th>
                  <th scope="col">Assigned</th>
                  <th scope="col">Depends on</th>
                  <th scope="col"><span className="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {project.milestones.map((milestone) => {
                  const blocking = blockingMilestones(milestone, project.milestones);
                  return (
                    <tr key={milestone.id} aria-selected={historyFor?.id === milestone.id}>
                      <td>
                        <strong>{milestone.name}</strong>
                        {milestone.description && <p className="muted small">{milestone.description}</p>}
                      </td>
                      <td>
                        {archived ? <StatusBadge status={milestone.status} /> : (
                          <select aria-label={`Status of ${milestone.name}`} value={milestone.status} onChange={(event) => act(() => projectsApi.setMilestoneStatus(projectId, milestone.id, event.target.value))}>
                            {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
                          </select>
                        )}
                      </td>
                      <td className="nowrap">{`${formatDate(milestone.startDate)} – ${formatDate(milestone.endDate)}`}</td>
                      <td className="numeric">{formatHours(milestone.progressTime)}</td>
                      <td>{milestone.assignedEmployees.length ? names(milestone.assignedEmployees) : <span className="muted">Nobody</span>}</td>
                      <td>
                        {milestone.dependsOn.length === 0 ? <span className="muted">—</span>
                          : milestone.dependsOn.map((id) => milestonesById.get(id)?.name ?? id.slice(0, 8)).join(', ')}
                        {blocking.length > 0 && <p className="availability blocked small">{`Waiting on ${blocking.join(', ')}`}</p>}
                      </td>
                      <td className="row-actions">
                        <button type="button" className="link-button" onClick={() => setHistoryFor(milestone)}>History</button>
                        {!archived && <button type="button" className="link-button" onClick={() => setMilestoneForm({ milestone })}>Edit</button>}
                        {!archived && (
                          <button type="button" className="link-button danger" onClick={() => window.confirm(`Archive milestone ${milestone.name}?`) && act(() => projectsApi.archiveMilestone(projectId, milestone.id))}>
                            Archive
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {showArchivedMilestones && (
          <div className="archived-section">
            <h3>Archived milestones</h3>
            <ErrorMessage error={archivedMilestones.error} onRetry={archivedMilestones.reload} />
            {archivedMilestones.loading && <p className="muted" role="status">Loading…</p>}
            {archivedMilestones.data?.length === 0 && <p className="muted">No archived milestones.</p>}
            {archivedMilestones.data?.length > 0 && (
              <ul className="plain-list">
                {archivedMilestones.data.map((milestone) => (
                  <li key={milestone.id}>
                    <strong>{milestone.name}</strong>
                    {' '}
                    <StatusBadge status={milestone.status} />
                    <span className="muted">{` · ${formatHours(milestone.progressTime)} · ${names(milestone.assignedEmployees) || 'nobody assigned'}`}</span>
                    {' '}
                    <button type="button" className="link-button" onClick={() => setHistoryFor(milestone)}>History</button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>

      <section>
        <div className="section-header">
          <h2>{historyFor ? `History of milestone ${historyFor.name}` : 'Project history'}</h2>
          {historyFor && <button type="button" onClick={() => setHistoryFor(undefined)}>Show whole project</button>}
        </div>
        <ErrorMessage error={history.error} onRetry={history.reload} />
        {history.loading && !history.data && <p className="muted" role="status">Loading history…</p>}
        {history.data && <AuditTable entries={history.data} names={{ users: lookups.users, milestones: milestonesById }} />}
      </section>

      {editing && (
        <Modal title={`Edit ${project.name}`} onClose={() => setEditing(false)}>
          <ProjectForm project={project} managers={lookups.managers} projects={data.active} onSubmit={saveProject} onCancel={() => setEditing(false)} />
        </Modal>
      )}
      {milestoneForm && (
        <Modal title={milestoneForm.milestone ? `Edit ${milestoneForm.milestone.name}` : 'Add milestone'} onClose={() => setMilestoneForm(undefined)}>
          <MilestoneForm
            milestone={milestoneForm.milestone}
            project={project}
            team={team}
            milestones={project.milestones}
            usersById={lookups.users}
            onSubmit={saveMilestone}
            onCancel={() => setMilestoneForm(undefined)}
          />
        </Modal>
      )}
    </main>
  );
}

ProjectDetailPage.propTypes = {
  projectId: PropTypes.string.isRequired,
};

export default ProjectDetailPage;
