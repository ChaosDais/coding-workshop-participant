import { useCallback, useState } from 'react';
import AuditTable from '../components/AuditTable.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import Loading from '../components/Loading.jsx';
import Pagination from '../components/Pagination.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { auditApi, usersApi } from '../services/api.js';
import { ACTION_GROUPS, actionLabel } from '../utils/audit.js';
import { byId } from '../utils/users.js';

const PAGE_SIZES = [10, 100, 250, 500];
const NO_FILTERS = {
  q: '', type: '', from: '', to: '',
};

async function loadPeople() {
  const [active, archived] = await Promise.all([usersApi.list(false), usersApi.list(true)]);
  return byId([...active, ...archived]);
}

/**
 * The whole audit log, newest first, searchable and filterable by type of change and date, in pages of 10 to 500
 * entries with page controls above and below the list.
 * @returns {JSX.Element} The audit log page.
 */
function AuditLogPage() {
  const [draft, setDraft] = useState(NO_FILTERS);
  const [filters, setFilters] = useState(NO_FILTERS);
  const [perPage, setPerPage] = useState(100);
  const [page, setPage] = useState(1);

  const people = useLoader(useCallback(loadPeople, []));
  const load = useCallback(() => {
    const actions = filters.type.startsWith('group:') ? ACTION_GROUPS[filters.type.slice(6)]
      : filters.type ? [filters.type] : [];
    return auditApi.page({
      q: filters.q.trim(), action: actions, from: filters.from, to: filters.to, limit: perPage, offset: (page - 1) * perPage,
    });
  }, [filters, perPage, page]);
  const { data, error, loading, reload } = useLoader(load);

  const set = (field) => (event) => setDraft((current) => ({ ...current, [field]: event.target.value }));
  const dateError = draft.from && draft.to && draft.to < draft.from ? 'The end date must not be before the start date' : undefined;

  const apply = (event) => {
    event.preventDefault();
    // Only new filters start again from page 1; Apply with the same filters keeps the page.
    if (!dateError && JSON.stringify(draft) !== JSON.stringify(filters)) {
      setFilters(draft);
      setPage(1);
    }
  };
  const clear = () => {
    setDraft(NO_FILTERS);
    if (JSON.stringify(filters) !== JSON.stringify(NO_FILTERS)) {
      setFilters(NO_FILTERS);
      setPage(1);
    }
  };
  const changePerPage = (event) => {
    setPerPage(Number(event.target.value));
    setPage(1);
  };

  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / perPage));
  const firstShown = data && data.entries.length > 0 ? data.offset + 1 : 0;
  const lastShown = data ? data.offset + data.entries.length : 0;
  const pager = (where) => (
    <Pagination page={Math.min(page, pages)} pages={pages} onChange={setPage} label={`Audit log pages, ${where}`} disabled={loading} />
  );

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
        <label htmlFor="audit-per-page">
          Per page
          <select id="audit-per-page" value={perPage} onChange={changePerPage}>
            {PAGE_SIZES.map((size) => <option key={size} value={size}>{size}</option>)}
          </select>
        </label>
        <div className="filter-actions">
          <button type="submit" className="primary" disabled={Boolean(dateError)}>Apply</button>
          <button type="button" onClick={clear}>Clear</button>
        </div>
      </form>
      {dateError && <p className="field-error" id="audit-date-error">{dateError}</p>}

      <ErrorMessage error={error ?? people.error} onRetry={error ? reload : people.reload} />
      {(loading || people.loading) && !(data && people.data) && <Loading>Loading the audit log…</Loading>}
      {data && people.data && (
        <>
          <div className="pagination-bar">
            <p className="muted" role="status">
              {total === 0 ? 'No entries' : `Showing ${firstShown}–${lastShown} of ${total} ${total === 1 ? 'entry' : 'entries'}`}
            </p>
            {pager('top')}
          </div>
          <AuditTable entries={data.entries} names={{ users: people.data }} empty="No entries match these filters." />
          {data.entries.length > 0 && <div className="pagination-bar bottom">{pager('bottom')}</div>}
        </>
      )}
    </main>
  );
}

export default AuditLogPage;
