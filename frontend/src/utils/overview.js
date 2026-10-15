import { availability, blockingMilestones, workloadByEmployee } from './projects.js';
import { addDays } from './workload.js';

const DAY_MS = 24 * 60 * 60 * 1000;
/** How far ahead "Due soon" looks. */
export const DUE_SOON_DAYS = 30;

const daysBetween = (from, to) => Math.round((Date.parse(to) - Date.parse(from)) / DAY_MS);

/**
 * The overview at the top of the project list: overall status, what needs attention and what is due soon, for the
 * projects the viewer can see (archived ones left out).
 * @param {object[]} projects Active projects, with their milestones.
 * @param {object} options Options.
 * @param {string} options.today "2026-10-15".
 * @param {{awaitingReviewDays: number}} options.thresholds Bottleneck thresholds.
 * @param {boolean} options.money Whether the viewer sees hours estimates and budgets (admins and managers).
 * @returns {object} statusCounts, milestones {total, complete}, issues (by kind, each with items, worst first),
 *   dueSoon (soonest first) and workloads (by employee id).
 */
export function overview(projects, { today, thresholds, money }) {
  const statusCounts = {
    TODO: 0, IN_PROGRESS: 0, AWAITING_REVIEW: 0, COMPLETE: 0,
  };
  projects.forEach((project) => { statusCounts[project.status] = (statusCounts[project.status] ?? 0) + 1; });

  const open = projects.filter((project) => project.status !== 'COMPLETE');
  const all = projects.flatMap((project) => project.milestones.map((milestone) => ({ project, milestone })));
  const openMilestones = open.flatMap((project) => project.milestones
    .filter((milestone) => milestone.status !== 'COMPLETE')
    .map((milestone) => ({ project, milestone })));
  const byProject = new Map(projects.map((project) => [project.id, project]));

  const issues = [
    {
      kind: 'overdueMilestones',
      severity: 'critical',
      label: (count) => `${count} overdue ${count === 1 ? 'milestone' : 'milestones'}`,
      items: openMilestones
        .filter(({ milestone }) => milestone.endDate && milestone.endDate < today)
        .map(({ project, milestone }) => ({
          key: milestone.id, projectId: project.id, name: `${project.name}: ${milestone.name}`,
          detail: `${daysBetween(milestone.endDate, today)} days overdue`, weight: daysBetween(milestone.endDate, today),
        })),
    },
    {
      kind: 'overdueProjects',
      severity: 'critical',
      label: (count) => `${count} overdue ${count === 1 ? 'project' : 'projects'}`,
      items: open
        .filter((project) => project.endDate && project.endDate < today)
        .map((project) => ({
          key: project.id, projectId: project.id, name: project.name,
          detail: `${daysBetween(project.endDate, today)} days past its end date`, weight: daysBetween(project.endDate, today),
        })),
    },
    {
      kind: 'blocked',
      severity: 'serious',
      label: (count) => `${count} blocked ${count === 1 ? 'project' : 'projects'} past their start date`,
      items: open
        .filter((project) => project.startDate && project.startDate <= today
          && availability(project, byProject).state === 'blocked')
        .map((project) => ({
          key: project.id, projectId: project.id, name: project.name,
          detail: availability(project, byProject).label, weight: daysBetween(project.startDate, today),
        })),
    },
    {
      kind: 'stalledReviews',
      severity: 'warning',
      label: (count) => `${count} ${count === 1 ? 'review' : 'reviews'} waiting ${thresholds.awaitingReviewDays}+ days`,
      items: openMilestones
        .filter(({ milestone }) => milestone.status === 'AWAITING_REVIEW' && milestone.statusChangedAt
          && daysBetween(milestone.statusChangedAt.slice(0, 10), today) >= thresholds.awaitingReviewDays)
        .map(({ project, milestone }) => {
          const days = daysBetween(milestone.statusChangedAt.slice(0, 10), today);
          return {
            key: milestone.id, projectId: project.id, name: `${project.name}: ${milestone.name}`, detail: `waiting ${days} days`, weight: days,
          };
        }),
    },
    {
      kind: 'unassigned',
      severity: 'warning',
      label: (count) => `${count} open ${count === 1 ? 'milestone' : 'milestones'} with nobody assigned`,
      items: openMilestones
        .filter(({ milestone }) => milestone.status !== 'AWAITING_REVIEW' && milestone.assignedEmployees.length === 0)
        .map(({ project, milestone }) => ({
          key: milestone.id, projectId: project.id, name: `${project.name}: ${milestone.name}`,
          detail: milestone.startDate && milestone.startDate <= today ? 'should have started' : `starts ${milestone.startDate ?? 'soon'}`,
          weight: milestone.startDate ? daysBetween(milestone.startDate, today) : 0,
        })),
    },
    ...(money ? [
      {
        kind: 'overHours',
        severity: 'warning',
        label: (count) => `${count} ${count === 1 ? 'project' : 'projects'} over expected hours`,
        items: open
          .filter((project) => project.expectedHours && project.progressTime > project.expectedHours)
          .map((project) => ({
            key: project.id, projectId: project.id, name: project.name,
            detail: `${Math.round((project.progressTime / project.expectedHours) * 100)}% of expected hours`,
            weight: project.progressTime / project.expectedHours,
          })),
      },
      {
        kind: 'overBudget',
        severity: 'serious',
        label: (count) => `${count} ${count === 1 ? 'project' : 'projects'} over budget`,
        items: projects
          .filter((project) => project.spent !== undefined && project.budget > 0 && Number(project.spent) > Number(project.budget))
          .map((project) => ({
            key: project.id, projectId: project.id, name: project.name,
            detail: `${Math.round((Number(project.spent) / Number(project.budget)) * 100)}% of budget spent`,
            weight: Number(project.spent) / Number(project.budget),
          })),
      },
    ] : []),
  ].map((issue) => ({ ...issue, items: [...issue.items].sort((a, b) => b.weight - a.weight) }))
    .filter((issue) => issue.items.length > 0);

  const horizon = addDays(today, DUE_SOON_DAYS);
  const dueSoon = openMilestones
    .filter(({ milestone }) => milestone.endDate && milestone.endDate >= today && milestone.endDate <= horizon)
    .map(({ project, milestone }) => ({
      project, milestone, inDays: daysBetween(today, milestone.endDate),
    }))
    .sort((a, b) => a.milestone.endDate.localeCompare(b.milestone.endDate) || a.project.name.localeCompare(b.project.name));

  return {
    statusCounts,
    milestones: { total: all.length, complete: all.filter(({ milestone }) => milestone.status === 'COMPLETE').length },
    issues,
    dueSoon,
    workloads: workloadByEmployee(projects),
  };
}

/**
 * An employee's own summary: their progress on their workload, each milestone they are assigned to, and the pain
 * points and deadlines among those milestones only.
 * @param {object[]} projects The projects they can see, with hoursByMilestone holding their own hours.
 * @param {object} options Options.
 * @param {string} options.today "2026-10-15".
 * @param {{awaitingReviewDays: number}} options.thresholds Bottleneck thresholds.
 * @param {string} options.userId The employee.
 * @returns {object} workload {hours, logged, milestones, unestimated}, milestones (open first, by due date, each
 *   with their own hours, their share of the estimate and what blocks it), complete (how many of theirs are
 *   complete), issues and dueSoon, shaped as in overview().
 */
export function employeeOverview(projects, { today, thresholds, userId }) {
  const load = workloadByEmployee(projects).get(userId);
  const own = (project, milestone) => Number(project.hoursByMilestone?.[milestone.id]?.[userId] ?? 0);
  const mine = projects.flatMap((project) => project.milestones
    .filter((milestone) => milestone.assignedEmployees.includes(userId))
    .map((milestone) => ({
      project,
      milestone,
      hours: own(project, milestone),
      share: load?.shares.get(milestone.id),
      blockedBy: milestone.status === 'COMPLETE' ? [] : blockingMilestones(milestone, project.milestones),
    })))
    .sort((a, b) => (a.milestone.status === 'COMPLETE') - (b.milestone.status === 'COMPLETE')
      || (a.milestone.endDate ?? '9999').localeCompare(b.milestone.endDate ?? '9999'));
  const open = mine.filter(({ milestone }) => milestone.status !== 'COMPLETE');
  const logged = open.filter(({ milestone }) => load?.shares.has(milestone.id))
    .reduce((sum, row) => sum + row.hours, 0);

  const issues = [
    {
      kind: 'overdueMilestones',
      severity: 'critical',
      label: (count) => `${count} of your milestones ${count === 1 ? 'is' : 'are'} overdue`,
      items: open.filter(({ milestone }) => milestone.endDate && milestone.endDate < today)
        .map(({ project, milestone }) => ({
          key: milestone.id, projectId: project.id, name: `${project.name}: ${milestone.name}`,
          detail: `${daysBetween(milestone.endDate, today)} days overdue`, weight: daysBetween(milestone.endDate, today),
        })),
    },
    {
      kind: 'blockedMilestones',
      severity: 'serious',
      label: (count) => `${count} waiting on other milestones`,
      items: open.filter(({ blockedBy }) => blockedBy.length > 0)
        .map(({ project, milestone, blockedBy }) => ({
          key: milestone.id, projectId: project.id, name: `${project.name}: ${milestone.name}`,
          detail: `waiting on ${blockedBy.join(', ')}`, weight: blockedBy.length,
        })),
    },
    {
      kind: 'stalledReviews',
      severity: 'warning',
      label: (count) => `${count} waiting for review ${thresholds.awaitingReviewDays}+ days`,
      items: open.filter(({ milestone }) => milestone.status === 'AWAITING_REVIEW' && milestone.statusChangedAt
        && daysBetween(milestone.statusChangedAt.slice(0, 10), today) >= thresholds.awaitingReviewDays)
        .map(({ project, milestone }) => {
          const days = daysBetween(milestone.statusChangedAt.slice(0, 10), today);
          return {
            key: milestone.id, projectId: project.id, name: `${project.name}: ${milestone.name}`, detail: `waiting ${days} days`, weight: days,
          };
        }),
    },
  ].map((issue) => ({ ...issue, items: [...issue.items].sort((a, b) => b.weight - a.weight) }))
    .filter((issue) => issue.items.length > 0);

  const horizon = addDays(today, DUE_SOON_DAYS);
  return {
    workload: {
      hours: load?.hours ?? 0, logged, milestones: load?.milestones ?? 0, unestimated: load?.unestimated ?? 0,
    },
    milestones: mine,
    complete: mine.length - open.length,
    issues,
    dueSoon: open.filter(({ milestone }) => milestone.endDate && milestone.endDate >= today && milestone.endDate <= horizon)
      .map(({ project, milestone }) => ({ project, milestone, inDays: daysBetween(today, milestone.endDate) })),
  };
}
