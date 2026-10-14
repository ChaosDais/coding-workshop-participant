import {
  useCallback, useEffect, useMemo, useState,
} from 'react';
import PropTypes from 'prop-types';
import AssigneeList from '../components/AssigneeList.jsx';
import AuditTable from '../components/AuditTable.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import HoursProgress from '../components/HoursProgress.jsx';
import LogTimeForm from '../components/LogTimeForm.jsx';
import TimeEntries from '../components/TimeEntries.jsx';
import MilestoneForm from '../components/MilestoneForm.jsx';
import Modal from '../components/Modal.jsx';
import ProjectForm from '../components/ProjectForm.jsx';
import StatusAge from '../components/StatusAge.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { auditApi, projectsApi, timeApi } from '../services/api.js';
import { useTimer } from '../timer/TimerContext.jsx';
import { loadProjectsAndPeople } from '../services/projectData.js';
import { availability, blockingMilestones, openMilestoneByEmployee } from '../utils/projects.js';
import {
  STATUSES, formatDate, formatMoney, formatTimestamp, statusLabel,
} from '../utils/format.js';
import { byId } from '../utils/users.js';

/** The request body for changing a project's status, keeping everything else as it is. */
function withStatus(project, status) {
  return {
    name: project.name,
    description: project.description ?? '',
    startDate: project.startDate,
    endDate: project.endDate,
    manager: project.manager,
    budget: project.budget,
    expectedHours: project.expectedHours ?? undefined,
    status,
    dependsOn: project.dependsOn,
  };
}

/**
 * One project in full: details, budget and spending, dependencies, milestones with progress against expected hours
 * and their assignees. Admins and the project's manager can change statuses, add, edit and archive milestones, and
 * assign, move and remove employees. Admins can also edit, archive and restore the project and see its history.
 * Everyone logs their own time here, by hand or with the timer: employees on their own milestones, which they can
 * also send for review, and admins and managers on any unfinished milestone. Employees see no money figures.
 * @param {object} props Component props.
 * @param {string} props.projectId The project's id.
 * @returns {JSX.Element} The project view.
 */
function ProjectDetailPage({ projectId }) {
  const { user } = useAuth();
  const isAdmin = user.role === 'ADMIN';
  const canManage = user.role !== 'EMPLOYEE';
  const timer = useTimer();
  const load = useCallback(() => loadProjectsAndPeople(user), [user]);
  const { data, error, loading, reload } = useLoader(load);
  const [showArchivedMilestones, setShowArchivedMilestones] = useState(false);
  const [historyFor, setHistoryFor] = useState();
  const [editing, setEditing] = useState(false);
  const [milestoneForm, setMilestoneForm] = useState();
  const [actionError, setActionError] = useState();
  const [busy, setBusy] = useState(false);
  const [logFor, setLogFor] = useState();
  const [timeFor, setTimeFor] = useState();

  const loadArchived = useCallback(
    () => (showArchivedMilestones ? projectsApi.milestones(projectId, true) : Promise.resolve([])),
    [projectId, showArchivedMilestones],
  );
  const archivedMilestones = useLoader(loadArchived);

  // The audit log is for admins only.
  const loadHistory = useCallback(
    () => (isAdmin ? auditApi.list(historyFor ? { milestoneId: historyFor.id } : { projectId }) : Promise.resolve([])),
    [isAdmin, projectId, historyFor],
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
      managers: data.users.filter((person) => person.role === 'MANAGER' && !person.archived),
    };
  }, [data]);

  const project = lookups?.projects.get(projectId);
  const archived = Boolean(data?.archived.some((found) => found.id === projectId));

  const refresh = () => {
    reload();
    archivedMilestones.reload();
    history.reload();
  };

  // Time logged through the timer bar changes progress, so show it.
  useEffect(() => {
    if (timer.logged > 0) {
      reload();
    }
  }, [timer.logged, reload]);

  const act = async (action) => {
    setActionError(undefined);
    setBusy(true);
    try {
      await action();
      refresh();
    } catch (failure) {
      setActionError(failure);
    } finally {
      setBusy(false);
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
        <p>This project does not exist, or it is not one of yours.</p>
        <a href="#/projects">Back to projects</a>
      </main>
    );
  }

  const editable = !archived;
  const projectAvailability = availability(project, lookups.projects);
  const milestonesById = new Map([...project.milestones, ...(archivedMilestones.data ?? [])].map((m) => [m.id, m]));
  const team = data.users.filter((person) => person.role === 'EMPLOYEE' && !person.archived && person.managerId === project.manager);
  const names = (ids) => ids.map((id) => lookups.users.get(id)?.name ?? `Former team member (${id.slice(0, 8)})`).join(', ');
  const dependencies = project.dependencies ?? project.dependsOn.map((id) => ({ id, ...lookups.projects.get(id) }));

  const logTime = async (hours, note) => {
    await timeApi.log(projectId, logFor.id, hours, note);
    setLogFor(undefined);
    timer.recordLogged();
    refresh();
  };

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
          {editable && canManage && (
            <label htmlFor="project-status-select" className="inline-label">
              Status
              <select id="project-status-select" value={project.status} disabled={busy} onChange={(event) => act(() => projectsApi.update(projectId, withStatus(project, event.target.value)))}>
                {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
              </select>
            </label>
          )}
          {isAdmin && editable && <button type="button" onClick={() => setEditing(true)}>Edit Project</button>}
          {isAdmin && (archived
            ? <button type="button" onClick={() => act(() => projectsApi.restore(projectId))}>Restore Project</button>
            : (
              <button type="button" className="danger" onClick={() => window.confirm(`Archive ${project.name}? It can be restored later.`) && act(() => projectsApi.archive(projectId))}>
                Archive Project
              </button>
            ))}
        </div>
      </div>
      <ErrorMessage error={actionError} />

      <dl className="summary">
        {isAdmin && <div><dt>Manager</dt><dd>{lookups.users.get(project.manager)?.name ?? project.manager}</dd></div>}
        <div><dt>Dates</dt><dd>{`${formatDate(project.startDate)} – ${formatDate(project.endDate)}`}</dd></div>
        {canManage && project.status !== 'COMPLETE' && project.statusChangedAt && (
          <div><dt>Time in status</dt><dd><StatusAge item={project} /></dd></div>
        )}
        <div>
          <dt>Progress</dt>
          <dd><HoursProgress progress={project.progressTime} expected={project.expectedHours} showOverrun={canManage} label={project.name} /></dd>
        </div>
        {project.budget !== undefined && <div><dt>Budget</dt><dd>{formatMoney(project.budget)}</dd></div>}
        {project.spent !== undefined && <div><dt>Spent</dt><dd>{formatMoney(project.spent)}</dd></div>}
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
        {dependencies.length === 0 ? <p className="muted">No project dependencies.</p> : (
          <ul className="plain-list">
            {dependencies.map((dependency) => (
              <li key={dependency.id}>
                {!dependency.name && `Unknown project (${dependency.id.slice(0, 8)})`}
                {dependency.name && (lookups.projects.has(dependency.id)
                  ? <a href={`#/projects/${dependency.id}`}>{dependency.name}</a>
                  : dependency.name)}
                {' '}
                {dependency.status && <StatusBadge status={dependency.status} />}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <div className="section-header">
          <h2>Milestones</h2>
          <div className="toolbar">
            {canManage && (
              <label className="checkbox">
                <input type="checkbox" checked={showArchivedMilestones} onChange={(event) => setShowArchivedMilestones(event.target.checked)} />
                Show archived milestones
              </label>
            )}
            {editable && canManage && <button type="button" className="primary" onClick={() => setMilestoneForm({})}>Add Milestone</button>}
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
                  const mine = milestone.assignedEmployees.includes(user.id);
                  const canLog = editable && milestone.status !== 'COMPLETE' && (canManage || mine);
                  const timerHere = timer.timer?.milestoneId === milestone.id;
                  return (
                    <tr key={milestone.id} className={mine ? 'mine' : undefined} aria-selected={historyFor?.id === milestone.id}>
                      <td>
                        <strong>{milestone.name}</strong>
                        {mine && <span className="badge you-badge">You</span>}
                        {milestone.description && <p className="muted small">{milestone.description}</p>}
                      </td>
                      <td>
                        {editable && canManage ? (
                          <select aria-label={`Status of ${milestone.name}`} value={milestone.status} disabled={busy} onChange={(event) => act(() => projectsApi.setMilestoneStatus(projectId, milestone.id, event.target.value))}>
                            {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
                          </select>
                        ) : <StatusBadge status={milestone.status} />}
                        {canManage && <StatusAge item={milestone} />}
                        {!canManage && mine && editable && !['AWAITING_REVIEW', 'COMPLETE'].includes(milestone.status) && (
                          <button type="button" className="link-button" disabled={busy} onClick={() => act(() => projectsApi.setMilestoneStatus(projectId, milestone.id, 'AWAITING_REVIEW'))}>
                            Send for Review
                          </button>
                        )}
                      </td>
                      <td className="nowrap">{`${formatDate(milestone.startDate)} – ${formatDate(milestone.endDate)}`}</td>
                      <td className="numeric">
                        <HoursProgress progress={milestone.progressTime} expected={milestone.expectedHours} showOverrun={canManage} label={milestone.name} />
                      </td>
                      <td>
                        <AssigneeList
                          milestone={milestone}
                          otherMilestones={project.milestones.filter((other) => other.id !== milestone.id)}
                          team={team}
                          workingOn={openMilestoneByEmployee(project, milestone.id)}
                          usersById={lookups.users}
                          editable={editable && canManage}
                          busy={busy}
                          onAssign={(employeeId) => act(() => projectsApi.assign(projectId, milestone.id, employeeId))}
                          onUnassign={(employeeId) => act(() => projectsApi.unassign(projectId, milestone.id, employeeId))}
                          onMove={(employeeId, target) => act(() => projectsApi.move(projectId, milestone.id, employeeId, target))}
                        />
                      </td>
                      <td>
                        {milestone.dependsOn.length === 0 ? <span className="muted">—</span>
                          : milestone.dependsOn.map((id) => milestonesById.get(id)?.name ?? id.slice(0, 8)).join(', ')}
                        {blocking.length > 0 && <p className="availability blocked small">{`Waiting on ${blocking.join(', ')}`}</p>}
                      </td>
                      <td className="row-actions">
                        {canLog && <button type="button" className="link-button" onClick={() => setLogFor(milestone)}>Log Time</button>}
                        {canLog && (timerHere ? <span className="muted small">Timer running</span> : (
                          <button
                            type="button"
                            className="link-button"
                            disabled={busy || Boolean(timer.timer)}
                            title={timer.timer ? 'Stop your running timer first' : undefined}
                            onClick={() => act(() => timer.start(project, milestone))}
                          >
                            Start Timer
                          </button>
                        ))}
                        {(canManage || mine) && <button type="button" className="link-button" onClick={() => setTimeFor(milestone)}>Time Log</button>}
                        {isAdmin && <button type="button" className="link-button" onClick={() => setHistoryFor(milestone)}>History</button>}
                        {editable && canManage && <button type="button" className="link-button" onClick={() => setMilestoneForm({ milestone })}>Edit</button>}
                        {editable && canManage && (
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
                    <span className="muted">{` · ${milestone.progressTime} h · ${names(milestone.assignedEmployees) || 'nobody assigned'}`}</span>
                    {' '}
                    {isAdmin && <button type="button" className="link-button" onClick={() => setHistoryFor(milestone)}>History</button>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>

      {isAdmin && (
        <section>
          <div className="section-header">
            <h2>{historyFor ? `History of milestone ${historyFor.name}` : 'Project history'}</h2>
            {historyFor && <button type="button" onClick={() => setHistoryFor(undefined)}>Show whole project</button>}
          </div>
          <ErrorMessage error={history.error} onRetry={history.reload} />
          {history.loading && !history.data && <p className="muted" role="status">Loading history…</p>}
          {history.data && <AuditTable entries={history.data} names={{ users: lookups.users, milestones: milestonesById }} />}
        </section>
      )}

      {editing && (
        <Modal title={`Edit ${project.name}`} onClose={() => setEditing(false)}>
          <ProjectForm project={project} managers={lookups.managers} projects={data.active} onSubmit={saveProject} onCancel={() => setEditing(false)} />
        </Modal>
      )}
      {logFor && (
        <Modal title={`Log Time on ${logFor.name}`} onClose={() => setLogFor(undefined)}>
          <LogTimeForm onSubmit={logTime} onCancel={() => setLogFor(undefined)} />
        </Modal>
      )}
      {timeFor && (
        <Modal title={`Time Logged on ${timeFor.name}`} onClose={() => setTimeFor(undefined)}>
          <TimeEntries projectId={projectId} milestoneId={timeFor.id} usersById={lookups.users} />
        </Modal>
      )}
      {milestoneForm && (
        <Modal title={milestoneForm.milestone ? `Edit ${milestoneForm.milestone.name}` : 'Add Milestone'} onClose={() => setMilestoneForm(undefined)}>
          <MilestoneForm
            milestone={milestoneForm.milestone}
            project={project}
            team={team}
            milestones={project.milestones}
            workingOn={openMilestoneByEmployee(project, milestoneForm.milestone?.id)}
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
