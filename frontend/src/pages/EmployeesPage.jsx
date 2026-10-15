import {
  Fragment, useCallback, useMemo, useState,
} from 'react';
import EmployeeWork from '../components/EmployeeWork.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import { ChevronIcon } from '../components/Icons.jsx';
import ManagerWork from '../components/ManagerWork.jsx';
import Workload from '../components/Workload.jsx';
import Loading from '../components/Loading.jsx';
import Modal from '../components/Modal.jsx';
import ResetPasswordForm from '../components/ResetPasswordForm.jsx';
import UserForm from '../components/UserForm.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { useToggleSet } from '../hooks/useToggleSet.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { projectsApi, usersApi } from '../services/api.js';
import { ROLES, formatMoney, roleLabel } from '../utils/format.js';
import { workloadByEmployee } from '../utils/projects.js';
import { byId, filterUsers } from '../utils/users.js';

async function loadPeople() {
  const [active, archived, projects] = await Promise.all([usersApi.list(false), usersApi.list(true),
    projectsApi.list(false)]);
  return { active, archived, projects };
}

/**
 * Everyone with an account: employees, managers and admins, with their email, id, job and salary, and each
 * employee's workload.
 * Admins can search, filter by role, add, edit, archive and restore people, and set someone's password.
 * Opening an employee shows their assignments and logged time; opening a manager shows their projects, with
 * milestones and assignees, and their team.
 * @returns {JSX.Element} The employees page.
 */
function EmployeesPage() {
  const { user: me } = useAuth();
  const { data, error, loading, reload } = useLoader(useCallback(loadPeople, []));
  const [search, setSearch] = useState('');
  const [role, setRole] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState();
  const [actionError, setActionError] = useState();
  const [resetting, setResetting] = useState();
  const [notice, setNotice] = useState();
  const [expanded, toggle] = useToggleSet();

  const everyone = useMemo(() => (data ? byId([...data.active, ...data.archived]) : new Map()), [data]);
  const workloads = useMemo(() => workloadByEmployee(data?.projects ?? []), [data]);
  const managers = data ? data.active.filter((user) => user.role === 'MANAGER') : [];
  const rows = data ? filterUsers([...data.active, ...(showArchived ? data.archived : [])], { search, role }) : [];

  const act = async (action) => {
    setActionError(undefined);
    setNotice(undefined);
    try {
      await action();
      reload();
    } catch (failure) {
      setActionError(failure);
    }
  };

  const save = async (body) => {
    if (form.user) {
      await usersApi.update(form.user.id, body);
    } else {
      await usersApi.create(body);
    }
    setForm(undefined);
    reload();
  };

  const resetPassword = async (newPassword) => {
    await usersApi.resetPassword(resetting.id, newPassword);
    setNotice(`${resetting.name}’s password has been changed and they have been signed out.`);
    setResetting(undefined);
  };

  return (
    <main className="page">
      <div className="page-header">
        <h1>Employees</h1>
        <button type="button" className="primary" onClick={() => setForm({})} disabled={!data}>Create new user</button>
      </div>

      <div className="filters">
        <label htmlFor="people-search">
          Search
          <input id="people-search" type="search" placeholder="Name, email, job or ID" value={search} onChange={(event) => setSearch(event.target.value)} />
        </label>
        <label htmlFor="people-role">
          Role
          <select id="people-role" value={role} onChange={(event) => setRole(event.target.value)}>
            <option value="">All roles</option>
            {ROLES.map((value) => <option key={value} value={value}>{roleLabel(value)}</option>)}
          </select>
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={showArchived} onChange={(event) => setShowArchived(event.target.checked)} />
          Show archived users
        </label>
      </div>

      <ErrorMessage error={error} onRetry={reload} />
      <ErrorMessage error={actionError} />
      {notice && <p className="success" role="status">{notice}</p>}
      {loading && !data && <Loading>Loading users…</Loading>}

      {data && (rows.length === 0 ? <p className="muted">No users match these filters.</p> : (
        <div className="table-wrap">
          <table className="clickable">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">ID</th>
                <th scope="col">Role</th>
                <th scope="col">Job</th>
                <th scope="col">Workload</th>
                <th scope="col" className="numeric">Salary</th>
                <th scope="col">Manager</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((person) => {
                // Employees open to show their work; managers their projects and team.
                const expandable = person.role === 'EMPLOYEE' || person.role === 'MANAGER';
                const open = expandable && expanded.has(person.id);
                const classes = [person.archived && 'archived', open && 'expanded', !expandable && 'static'].filter(Boolean).join(' ');
                return (
                  <Fragment key={person.id}>
                    <tr className={classes || undefined} onClick={expandable ? () => toggle(person.id) : undefined}>
                      <td>
                        {expandable ? (
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
                        ) : person.name}
                        {person.archived && <span className="badge archived-badge">Archived</span>}
                      </td>
                      <td>{person.email}</td>
                      <td><code title={person.id}>{person.id.slice(0, 8)}</code></td>
                      <td>{roleLabel(person.role)}</td>
                      <td>{person.jobType ?? <span className="muted">—</span>}</td>
                      <td>{person.role === 'EMPLOYEE' ? <Workload load={workloads.get(person.id)} /> : <span className="muted">—</span>}</td>
                      <td className="numeric">{formatMoney(person.salary)}</td>
                      <td>{person.managerId ? everyone.get(person.managerId)?.name ?? '—' : <span className="muted">—</span>}</td>
                      <td className="row-actions" onClick={(event) => event.stopPropagation()}>
                        {person.archived ? (
                          <button type="button" className="link-button" onClick={() => act(() => usersApi.restore(person.id))}>Restore</button>
                        ) : (
                          <>
                            <button type="button" className="link-button" onClick={() => setForm({ user: person })}>Edit</button>
                            {person.id !== me.id && (
                              <button type="button" className="link-button" onClick={() => { setNotice(undefined); setResetting(person); }}>
                                Reset password
                              </button>
                            )}
                            {person.id !== me.id && (
                              <button type="button" className="link-button danger" onClick={() => window.confirm(`Archive ${person.name}? They will be signed out and can be restored later.`) && act(() => usersApi.archive(person.id))}>
                                Archive
                              </button>
                            )}
                          </>
                        )}
                      </td>
                    </tr>
                    {open && (
                      <tr className="preview-row" id={`work-${person.id}`}>
                        <td colSpan={9}>
                          {person.role === 'MANAGER' ? <ManagerWork manager={person} people={everyone} projects={data.projects} workloads={workloads} />
                            : <EmployeeWork person={person} shares={workloads.get(person.id)?.shares} />}
                        </td>
                      </tr>
                    )}
                    </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      ))}

      {resetting && (
        <Modal title={`Reset password for ${resetting.name}`} onClose={() => setResetting(undefined)}>
          <ResetPasswordForm user={resetting} onSubmit={resetPassword} onCancel={() => setResetting(undefined)} />
        </Modal>
      )}
      {form && (
        <Modal title={form.user ? `Edit ${form.user.name}` : 'Add person'} onClose={() => setForm(undefined)}>
          <UserForm user={form.user} managers={managers} onSubmit={save} onCancel={() => setForm(undefined)} />
        </Modal>
      )}
    </main>
  );
}

export default EmployeesPage;
