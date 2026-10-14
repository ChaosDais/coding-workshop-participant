import { useCallback, useState } from 'react';
import AuditTable from '../components/AuditTable.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { auditApi, usersApi } from '../services/api.js';
import { ACTION_GROUPS, actionLabel } from '../utils/audit.js';
import { byId } from '../utils/users.js';

const LIMITS = [100, 250, 500];

/**
 * The whole audit log, newest first, searchable and filterable by type of change and date.
 * @returns {JSX.Element} The audit log page.
 */
function AuditLogPage() {
  const [draft, setDraft] = useState({
    q: '', type: '', from: '', to: '', limit: 100,
  });
  const [filters, setFilters] = useState(draft);

  const load = useCallback(async () => {
    const actions = filters.type.startsWith('group:') ? ACTION_GROUPS[filters.type.slice(6)]
      : filters.type ? [filters.type] : [];
    const [entries, active, archived] = await Promise.all([
      auditApi.list({
        q: filters.q.trim(), action: actions, from: filters.from, to: filters.to, limit: filters.limit,
      }),
      usersApi.list(false),
      usersApi.list(true),
    ]);
    return { entries, users: byId([...active, ...archived]) };
  }, [filters]);
  const { data, error, loading, reload } = useLoader(load);

  const set = (field) => (event) => setDraft((current) => ({ ...current, [field]: event.target.value }));
  const dateError = draft.from && draft.to && draft.to < draft.from ? 'The end date must not be before the start date' : undefined;

  const apply = (event) => {
    event.preventDefault();
    if (!dateError) {
      setFilters({ ...draft, limit: Number(draft.limit) });
    }
  };
  const clear = () => {
    const empty = {
      q: '', type: '', from: '', to: '', limit: 100,
    };
    setDraft(empty);
    setFilters(empty);
  };

  return (
    <main className="page">
      <div className="page-header">
        <h1>Audit Log</h1>
      </div>
      <form className="filters" onSubmit={apply}>
        <label htmlFor="audit-search">
          Search
          <input id="audit-search" type="search" placeholder="Person, project, action…" value={draft.q} onChange={set('q')} />
        </label>
        <label htmlFor="audit-type">
          Type
          <select id="audit-type" value={draft.type} onChange={set('type')}>
            <option value="">All Changes</option>
            {Object.entries(ACTION_GROUPS).map(([group, actions]) => (
              <optgroup key={group} label={group}>
                <option value={`group:${group}`}>{`All ${group.toLowerCase()} Changes`}</option>
                {actions.map((action) => <option key={action} value={action}>{actionLabel(action)}</option>)}
              </optgroup>
            ))}
          </select>
        </label>
        <label htmlFor="audit-from">
          From
          <input id="audit-from" type="date" value={draft.from} onChange={set('from')} />
        </label>
        <label htmlFor="audit-to">
          To
          <input id="audit-to" type="date" value={draft.to} min={draft.from || undefined} onChange={set('to')} aria-invalid={Boolean(dateError)} aria-describedby={dateError ? 'audit-date-error' : undefined} />
        </label>
        <label htmlFor="audit-limit">
          Show
          <select id="audit-limit" value={draft.limit} onChange={set('limit')}>
            {LIMITS.map((limit) => <option key={limit} value={limit}>{`Latest ${limit}`}</option>)}
          </select>
        </label>
        <div className="filter-actions">
          <button type="submit" className="primary" disabled={Boolean(dateError)}>Apply</button>
          <button type="button" onClick={clear}>Clear</button>
        </div>
      </form>
      {dateError && <p className="field-error" id="audit-date-error">{dateError}</p>}

      <ErrorMessage error={error} onRetry={reload} />
      {loading && <p className="muted" role="status">Loading the audit log…</p>}
      {data && (
        <>
          <p className="muted">{`${data.entries.length} ${data.entries.length === 1 ? 'entry' : 'entries'}${data.entries.length === filters.limit ? ' (more may exist; narrow the filters or show more)' : ''}`}</p>
          <AuditTable entries={data.entries} names={{ users: data.users }} empty="No entries match these filters." />
        </>
      )}
    </main>
  );
}

export default AuditLogPage;
