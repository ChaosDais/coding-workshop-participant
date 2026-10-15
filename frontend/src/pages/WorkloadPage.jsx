import { useCallback, useMemo, useState } from 'react';
import ErrorMessage from '../components/ErrorMessage.jsx';
import Loading from '../components/Loading.jsx';
import WorkloadChart from '../components/WorkloadChart.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { useLoader } from '../hooks/useLoader.js';
import { projectsApi, timeApi, usersApi } from '../services/api.js';
import { formatHours } from '../utils/format.js';
import { workloadByEmployee } from '../utils/projects.js';
import {
  RANGES, dayOf, niceMax, progressSeries, rangeDays,
} from '../utils/workload.js';

/** What the charts can be sorted by, and the direction each starts in when picked. */
const SORTS = [
  { value: 'name', label: 'Name', direction: 'asc' },
  { value: 'workload', label: 'Workload', direction: 'desc' },
  { value: 'logged', label: 'Logged', direction: 'desc' },
  { value: 'remaining', label: 'Remaining', direction: 'desc' },
  { value: 'done', label: '% done', direction: 'asc' },
];

/**
 * Orders two rows by the chosen field, then by name so ties keep a stable order.
 * @param {string} sort A SORTS value.
 * @param {'asc'|'desc'} direction Ascending or descending.
 * @returns {Function} A comparator for Array.prototype.sort.
 */
function compareRows(sort, direction) {
  const byName = (a, b) => a.person.name.localeCompare(b.person.name);
  return (a, b) => {
    const primary = sort === 'name' ? byName(a, b) : (a[sort] ?? -1) - (b[sort] ?? -1);
    return (direction === 'desc' ? -primary : primary) || byName(a, b);
  };
}

async function loadWorkload() {
  const [projects, users, daily] = await Promise.all([projectsApi.list(false), usersApi.list(false), timeApi.daily()]);
  return { projects, users, daily };
}

/**
 * Employee workloads over time. Each employee gets a small chart of the hours they have logged on the milestones
 * making up their current workload, against that workload, on one shared hours scale; a table below holds the
 * same figures. Admins see every employee and can pick a manager's team; managers see their own team.
 * @returns {JSX.Element} The workload page.
 */
function WorkloadPage() {
  const { user } = useAuth();
  const isAdmin = user.role === 'ADMIN';
  const { data, error, loading, reload } = useLoader(useCallback(loadWorkload, []));
  const [range, setRange] = useState(28);
  const [sort, setSort] = useState('name');
  const [direction, setDirection] = useState('asc');
  const chooseSort = (value) => {
    setSort(value);
    setDirection(SORTS.find((option) => option.value === value).direction);
  };
  const [search, setSearch] = useState('');
  const [managerId, setManagerId] = useState('');

  const rows = useMemo(() => {
    if (!data) {
      return [];
    }
    const workloads = workloadByEmployee(data.projects);
    const earliest = data.daily[0]?.day;
    const days = rangeDays(range, dayOf(new Date()), earliest);
    return data.users
      .filter((person) => person.role === 'EMPLOYEE' && (isAdmin || person.managerId === user.id))
      .map((person) => {
        const load = workloads.get(person.id);
        const workload = load?.hours ?? 0;
        const series = progressSeries(data.daily, person.id, new Set(load?.shares.keys() ?? []), days);
        const logged = series[series.length - 1].total;
        return {
          person,
          workload,
          series,
          logged,
          remaining: Math.max(workload - logged, 0),
          done: workload > 0 ? Math.round((logged / workload) * 100) : undefined,
          milestones: load?.milestones ?? 0,
          unestimated: load?.unestimated ?? 0,
        };
      });
  }, [data, range, isAdmin, user.id]);

  const managers = useMemo(() => (data ? data.users.filter((person) => person.role === 'MANAGER') : []), [data]);
  const managerName = (id) => managers.find((manager) => manager.id === id)?.name ?? '—';
  const needle = search.trim().toLowerCase();
  const visible = rows
    .filter((row) => !needle || row.person.name.toLowerCase().includes(needle) || (row.person.jobType ?? '').toLowerCase().includes(needle))
    .filter((row) => !managerId || row.person.managerId === managerId)
    .sort(compareRows(sort, direction));
  const charted = visible.filter((row) => row.workload > 0);
  const without = visible.filter((row) => row.workload === 0);
  const maxHours = niceMax(Math.max(0, ...charted.map((row) => Math.max(row.workload, row.logged))));

  return (
    <main className="page">
      <div className="page-header">
        <h1>{isAdmin ? 'Workload' : 'Team Workload'}</h1>
      </div>
      <p className="muted page-intro">
        Each chart shows the hours an employee has logged on the milestones making up their current workload (their
        share of those milestones&apos; expected hours, shown dashed). All charts use the same scale.
      </p>

      <div className="filters">
        <label htmlFor="workload-search">
          Search
          <input id="workload-search" type="search" placeholder="Name or job" value={search} onChange={(event) => setSearch(event.target.value)} />
        </label>
        {isAdmin && (
          <label htmlFor="workload-manager">
            Team
            <select id="workload-manager" value={managerId} onChange={(event) => setManagerId(event.target.value)}>
              <option value="">Everyone</option>
              {managers.map((manager) => <option key={manager.id} value={manager.id}>{manager.name}</option>)}
            </select>
          </label>
        )}
        <label htmlFor="workload-range">
          Dates
          <select id="workload-range" value={range} onChange={(event) => setRange(Number(event.target.value))}>
            {RANGES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <label htmlFor="workload-sort">
          Sort by
          <select id="workload-sort" value={sort} onChange={(event) => chooseSort(event.target.value)}>
            {SORTS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <button
          type="button"
          className="sort-direction"
          onClick={() => setDirection((current) => (current === 'asc' ? 'desc' : 'asc'))}
          aria-label={`Sort order: ${direction === 'asc' ? 'ascending' : 'descending'}. Switch to ${direction === 'asc' ? 'descending' : 'ascending'}`}
          title="Switch the sort order"
        >
          {direction === 'asc' ? '↑ Ascending' : '↓ Descending'}
        </button>
      </div>

      <ErrorMessage error={error} onRetry={reload} />
      {loading && !data && <Loading>Loading workloads…</Loading>}
      {data && rows.length === 0 && <p className="muted">{isAdmin ? 'There are no employees yet.' : 'No employees report to you yet.'}</p>}
      {data && rows.length > 0 && visible.length === 0 && <p className="muted">Nobody matches these filters.</p>}

      {charted.length > 0 && (
        <div className="workload-grid">
          {charted.map((row) => (
            <section key={row.person.id} className="workload-card" aria-label={row.person.name}>
              <div className="workload-card-header">
                <div>
                  <h2>{row.person.name}</h2>
                  <p className="muted small">{[row.person.jobType, isAdmin && managerName(row.person.managerId)].filter(Boolean).join(' · ') || ' '}</p>
                </div>
                <div className="workload-figures">
                  <strong>{`${formatHours(row.logged)} of ${formatHours(row.workload)}`}</strong>
                  {row.logged > row.workload
                    ? <span className="badge over-badge">{`⚠ Over by ${formatHours(row.logged - row.workload)}`}</span>
                    : <span className="muted small">{`${row.done}% done`}</span>}
                </div>
              </div>
              <WorkloadChart name={row.person.name} series={row.series} workload={row.workload} maxHours={maxHours} />
              <p className="muted small">
                {`${row.milestones} ${row.milestones === 1 ? 'milestone' : 'milestones'}`}
                {row.unestimated > 0 && ` (${row.unestimated} without an estimate)`}
              </p>
            </section>
          ))}
        </div>
      )}

      {without.length > 0 && (
        <p className="muted workload-without">
          {'No estimated workload: '}
          {without.map((row) => `${row.person.name}${row.unestimated > 0 ? ` (${row.unestimated} ${row.unestimated === 1 ? 'milestone' : 'milestones'} without an estimate)` : ''}`).join(', ')}
        </p>
      )}

      {visible.length > 0 && (
        <section>
          <h2>Summary</h2>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Employee</th>
                  {isAdmin && <th scope="col">Manager</th>}
                  <th scope="col" className="numeric">Workload</th>
                  <th scope="col" className="numeric">Logged on it</th>
                  <th scope="col" className="numeric">Remaining</th>
                  <th scope="col" className="numeric">Done</th>
                  <th scope="col" className="numeric">Milestones</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((row) => (
                  <tr key={row.person.id}>
                    <td>{row.person.name}</td>
                    {isAdmin && <td>{managerName(row.person.managerId)}</td>}
                    <td className="numeric nowrap">{formatHours(row.workload)}</td>
                    <td className="numeric nowrap">{formatHours(row.logged)}</td>
                    <td className="numeric nowrap">{formatHours(row.remaining)}</td>
                    <td className="numeric">{row.done === undefined ? '—' : `${row.done}%`}</td>
                    <td className="numeric">{row.unestimated > 0 ? `${row.milestones} (${row.unestimated} without an estimate)` : row.milestones}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </main>
  );
}

export default WorkloadPage;
