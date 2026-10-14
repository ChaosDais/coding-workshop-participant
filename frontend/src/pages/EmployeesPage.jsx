import { useCallback, useMemo, useState } from 'react';
import ErrorMessage from '../components/ErrorMessage.jsx';
import Modal from '../components/Modal.jsx';
import ResetPasswordForm from '../components/ResetPasswordForm.jsx';
import UserForm from '../components/UserForm.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { usersApi } from '../services/api.js';
import { ROLES, formatMoney, roleLabel } from '../utils/format.js';
import { byId, filterUsers } from '../utils/users.js';

async function loadPeople() {
  const [active, archived] = await Promise.all([usersApi.list(false), usersApi.list(true)]);
  return { active, archived };
}

/**
 * Everyone with an account: employees, managers and admins, with their email, id, job and salary.
 * Admins can search, filter by role, add, edit, archive and restore people, and set someone's password.
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

  const everyone = useMemo(() => (data ? byId([...data.active, ...data.archived]) : new Map()), [data]);
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
        <button type="button" className="primary" onClick={() => setForm({})} disabled={!data}>Add person</button>
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
          Show archived people
        </label>
      </div>

      <ErrorMessage error={error} onRetry={reload} />
      <ErrorMessage error={actionError} />
      {notice && <p className="success" role="status">{notice}</p>}
      {loading && !data && <p className="muted" role="status">Loading people…</p>}

      {data && (rows.length === 0 ? <p className="muted">Nobody matches these filters.</p> : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">ID</th>
                <th scope="col">Role</th>
                <th scope="col">Job</th>
                <th scope="col" className="numeric">Salary</th>
                <th scope="col">Manager</th>
                <th scope="col"><span className="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((person) => (
                <tr key={person.id} className={person.archived ? 'archived' : undefined}>
                  <td>
                    {person.name}
                    {person.archived && <span className="badge archived-badge">Archived</span>}
                  </td>
                  <td>{person.email}</td>
                  <td><code title={person.id}>{person.id.slice(0, 8)}</code></td>
                  <td>{roleLabel(person.role)}</td>
                  <td>{person.jobType ?? <span className="muted">—</span>}</td>
                  <td className="numeric">{formatMoney(person.salary)}</td>
                  <td>{person.managerId ? everyone.get(person.managerId)?.name ?? '—' : <span className="muted">—</span>}</td>
                  <td className="row-actions">
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
              ))}
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
