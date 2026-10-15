import { useState } from 'react';
import PropTypes from 'prop-types';
import StatusBadge from './StatusBadge.jsx';
import { formatHours } from '../utils/format.js';
import { DUE_SOON_DAYS } from '../utils/overview.js';

/** Status segments in the order they sit side by side; colours are validated for both themes in this order. */
const SEGMENTS = [
  { status: 'TODO', label: 'TODO' },
  { status: 'IN_PROGRESS', label: 'IN PROGRESS' },
  { status: 'AWAITING_REVIEW', label: 'AWAITING REVIEW' },
  { status: 'COMPLETE', label: 'COMPLETE' },
];
const SEVERITY_ICONS = { critical: '●', serious: '▲', warning: '◆' };
const DUE_SHOWN = 4;
const WORKLOAD_SHOWN = 5;

const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;
const shortDate = (day) => new Date(`${day}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Overall status: the share of milestones complete, and projects split by status on one bar. */
function StatusCard({ summary }) {
  const [focus, setFocus] = useState();
  const total = SEGMENTS.reduce((sum, segment) => sum + summary.statusCounts[segment.status], 0);
  const { complete, total: milestones } = summary.milestones;
  const percent = milestones ? Math.round((complete / milestones) * 100) : 0;
  const shown = SEGMENTS.find((segment) => segment.status === focus);

  return (
    <section className="overview-card" aria-labelledby="overview-status">
      <h2 id="overview-status" className="overview-title">Project status</h2>
      <p className="overview-hero">
        <strong>{`${percent}%`}</strong>
        <span>{`of milestones complete (${complete} of ${milestones})`}</span>
      </p>
      <p className="overview-caption" aria-live="polite">
        {shown
          ? `${shown.label}: ${plural(summary.statusCounts[shown.status], 'project')} (${Math.round((summary.statusCounts[shown.status] / total) * 100)}%)`
          : plural(total, 'project')}
      </p>
      {total > 0 && (
        <div className="status-bar" role="group" aria-label="Projects by status">
          {SEGMENTS.filter((segment) => summary.statusCounts[segment.status] > 0).map((segment) => (
            <span
              key={segment.status}
              className={`status-bar-segment viz-${segment.status.toLowerCase().replaceAll('_', '-')}`}
              style={{ flexGrow: summary.statusCounts[segment.status] }}
              role="img"
              tabIndex={0}
              aria-label={`${segment.label}: ${plural(summary.statusCounts[segment.status], 'project')}`}
              onPointerEnter={() => setFocus(segment.status)}
              onPointerLeave={() => setFocus(undefined)}
              onFocus={() => setFocus(segment.status)}
              onBlur={() => setFocus(undefined)}
            />
          ))}
        </div>
      )}
      <ul className="status-legend">
        {SEGMENTS.map((segment) => (
          <li key={segment.status}>
            <span className={`status-key viz-${segment.status.toLowerCase().replaceAll('_', '-')}`} aria-hidden="true" />
            <span>{segment.label}</span>
            <strong>{summary.statusCounts[segment.status]}</strong>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Pain points, worst first; each opens to name what is affected. */
function AttentionCard({ summary }) {
  const count = summary.issues.reduce((sum, issue) => sum + issue.items.length, 0);
  return (
    <section className="overview-card" aria-labelledby="overview-attention">
      <h2 id="overview-attention" className="overview-title">Needs attention</h2>
      {count === 0 ? (
        <p className="overview-hero all-clear">
          <strong>✓</strong>
          <span>Nothing overdue, blocked or stalled</span>
        </p>
      ) : (
        <>
          <p className="overview-hero">
            <strong>{count}</strong>
            <span>{count === 1 ? 'thing needs attention' : 'things need attention'}</span>
          </p>
          <ul className="attention-list">
            {summary.issues.map((issue) => (
              <li key={issue.kind}>
                <details>
                  <summary>
                    <span className={`severity severity-${issue.severity}`} aria-hidden="true">{SEVERITY_ICONS[issue.severity]}</span>
                    <span>{issue.label(issue.items.length)}</span>
                  </summary>
                  <ul>
                    {issue.items.map((item) => (
                      <li key={item.key}>
                        <a href={`#/projects/${item.projectId}`}>{item.name}</a>
                        <span className="muted small">{` · ${item.detail}`}</span>
                      </li>
                    ))}
                  </ul>
                </details>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

/** Milestones due in the next 30 days, soonest first, on a short timeline. */
function DueSoonCard({ summary }) {
  const shown = summary.dueSoon.slice(0, DUE_SHOWN);
  const more = summary.dueSoon.length - shown.length;
  return (
    <section className="overview-card" aria-labelledby="overview-due">
      <h2 id="overview-due" className="overview-title">Due soon</h2>
      <p className="overview-hero">
        <strong>{summary.dueSoon.length}</strong>
        <span>{`${summary.workload ? 'of your ' : ''}${summary.dueSoon.length === 1 ? 'milestone' : 'milestones'} due in ${DUE_SOON_DAYS} days`}</span>
      </p>
      {shown.length === 0 ? <p className="muted small">Nothing due in the next 30 days.</p> : (
        <ol className="due-timeline">
          {shown.map(({ project, milestone, inDays }) => (
            <li key={milestone.id}>
              <span className="due-date" aria-hidden="true">{shortDate(milestone.endDate)}</span>
              <span className="due-what">
                <a href={`#/projects/${project.id}`}>{`${project.name}: ${milestone.name}`}</a>
                <span className={`small ${inDays <= 3 ? 'due-close' : 'muted'}`}>
                  {inDays === 0 ? 'due today' : `in ${plural(inDays, 'day')}`}
                  <span className="visually-hidden">{`, ${shortDate(milestone.endDate)}`}</span>
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}
      {more > 0 && <p className="muted small">{`+ ${more} more`}</p>}
    </section>
  );
}

/**
 * An employee's progress: the hours they have logged against their workload, then each milestone they are on with
 * its status, their own time against their share of its estimate, and when it is due.
 */
function ProgressCard({ summary }) {
  const { hours, logged } = summary.workload;
  const percent = hours > 0 ? Math.round((logged / hours) * 100) : undefined;
  const open = summary.milestones.filter(({ milestone }) => milestone.status !== 'COMPLETE');
  return (
    <section className="overview-card overview-wide" aria-labelledby="overview-progress">
      <h2 id="overview-progress" className="overview-title">Your progress</h2>
      <div className="progress-head">
        <p className="overview-hero">
          <strong>{percent === undefined ? formatHours(logged) : `${percent}%`}</strong>
          <span>
            {hours > 0
              ? `of your workload logged (${formatHours(logged)} of ${formatHours(hours)})`
              : 'logged; none of your milestones has an estimate yet'}
          </span>
        </p>
        <p className="muted small">
          {`${plural(open.length, 'open milestone')}`}
          {summary.complete > 0 && ` · ${summary.complete} complete`}
          {summary.workload.unestimated > 0 && ` · ${summary.workload.unestimated} without an estimate`}
        </p>
      </div>
      {percent !== undefined && (
        <div
          className={`progress-meter${logged > hours ? ' over' : ''}`}
          role="meter"
          aria-label="Workload logged"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.min(percent, 100)}
          aria-valuetext={`${percent}% of your workload logged`}
        >
          <span style={{ width: `${Math.min(percent, 100)}%` }} />
        </div>
      )}
      {summary.milestones.length === 0 ? <p className="muted small">You are not assigned to any milestones yet.</p> : (
        <ul className="my-milestones">
          {summary.milestones.map(({
            project, milestone, hours: mine, share,
          }) => (
            <li key={milestone.id} className={milestone.status === 'COMPLETE' ? 'done' : undefined}>
              <a href={`#/projects/${project.id}`}>{`${project.name}: ${milestone.name}`}</a>
              <StatusBadge status={milestone.status} />
              <span className="my-milestone-hours">
                {share === undefined ? formatHours(mine) : `${formatHours(mine)} of ${formatHours(share)}`}
              </span>
              <span className="muted small nowrap">{milestone.endDate ? `due ${shortDate(milestone.endDate)}` : ''}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The heaviest workloads, as bars on one scale. */
function WorkloadCard({ summary, usersById }) {
  // People who are not (or no longer) accounts, e.g. on old data, are left out.
  const rows = [...summary.workloads.entries()]
    .filter(([id]) => usersById.has(id))
    .map(([id, load]) => ({ id, name: usersById.get(id).name, hours: load.hours }))
    .sort((a, b) => b.hours - a.hours || a.name.localeCompare(b.name));
  const top = rows.slice(0, WORKLOAD_SHOWN);
  const max = Math.max(1, ...top.map((row) => row.hours));
  return (
    <section className="overview-card" aria-labelledby="overview-workload">
      <h2 id="overview-workload" className="overview-title">Workload</h2>
      <p className="overview-hero">
        <strong>{rows.length}</strong>
        <span>{rows.length === 1 ? 'person with open work' : 'people with open work'}</span>
      </p>
      {top.length > 0 && (
        <ul className="workload-bars" aria-label="Largest workloads">
          {top.map((row) => (
            <li key={row.id}>
              <span className="workload-bar-name">{row.name}</span>
              <span className="workload-bar-track" aria-hidden="true">
                <span className="workload-bar-fill" style={{ width: `${(row.hours / max) * 100}%` }} />
              </span>
              <span className="workload-bar-value">{formatHours(row.hours)}</span>
            </li>
          ))}
        </ul>
      )}
      <a className="small" href="#/workload">See every workload →</a>
    </section>
  );
}

/**
 * The summary at the top of the project list: overall status, pain points such as overdue milestones, what is
 * due soon and the heaviest workloads, for the projects the viewer can see. Employees get their own progress and
 * the pain points and deadlines among their own milestones instead.
 * @param {object} props Component props.
 * @param {object} props.summary From overview(), or employeeOverview() for employees, in utils/overview.js.
 * @param {Map<string, {name: string}>} props.usersById People, to name workloads.
 * @param {boolean} props.isEmployee Whether the viewer is an employee.
 * @returns {JSX.Element} The overview.
 */
function Overview({
  summary, usersById, isEmployee,
}) {
  return (
    <div className="overview" role="region" aria-label="Overview">
      {isEmployee ? <ProgressCard summary={summary} /> : <StatusCard summary={summary} />}
      <AttentionCard summary={summary} />
      <DueSoonCard summary={summary} />
      {!isEmployee && <WorkloadCard summary={summary} usersById={usersById} />}
    </div>
  );
}

const summaryShape = PropTypes.shape({
  statusCounts: PropTypes.object,
  milestones: PropTypes.oneOfType([PropTypes.object, PropTypes.array]).isRequired,
  issues: PropTypes.arrayOf(PropTypes.object).isRequired,
  dueSoon: PropTypes.arrayOf(PropTypes.object).isRequired,
  workloads: PropTypes.instanceOf(Map),
  workload: PropTypes.object,
});
StatusCard.propTypes = { summary: summaryShape.isRequired };
AttentionCard.propTypes = { summary: summaryShape.isRequired };
DueSoonCard.propTypes = { summary: summaryShape.isRequired };
ProgressCard.propTypes = { summary: summaryShape.isRequired };
WorkloadCard.propTypes = {
  summary: summaryShape.isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
};
Overview.propTypes = {
  summary: summaryShape.isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
  isEmployee: PropTypes.bool.isRequired,
};

export default Overview;
