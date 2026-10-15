import { describe, expect, it } from 'vitest';
import { employeeOverview, overview } from './overview.js';

const milestone = (id, extra = {}) => ({
  id, name: id, status: 'IN_PROGRESS', startDate: '2026-09-01', endDate: '2026-12-01', assignedEmployees: ['e1'], ...extra,
});
const project = (id, extra = {}) => ({
  id, name: id, status: 'IN_PROGRESS', startDate: '2026-01-01', endDate: '2026-12-31', dependsOn: [], milestones: [],
  progressTime: 0, ...extra,
});
const options = { today: '2026-10-15', thresholds: { awaitingReviewDays: 3, anyStatusDays: 30 }, money: true };
const issue = (summary, kind) => summary.issues.find((found) => found.kind === kind);

describe('overview', () => {
  const projects = [
    project('Apollo', {
      expectedHours: 10, progressTime: 12, budget: 100, spent: 150,
      milestones: [
        milestone('Design', { status: 'COMPLETE' }),
        milestone('Build', { endDate: '2026-10-10' }),
        milestone('Test', { endDate: '2026-10-20', assignedEmployees: [] }),
        milestone('Review', { status: 'AWAITING_REVIEW', statusChangedAt: '2026-10-01T09:00:00Z', endDate: '2026-11-30' }),
      ],
    }),
    project('Gemini', { status: 'TODO', dependsOn: ['Apollo'], endDate: '2026-10-01' }),
    project('Pilot', { status: 'COMPLETE', endDate: '2026-06-30', milestones: [milestone('Done', { status: 'COMPLETE', endDate: '2026-06-01' })] }),
  ];

  it('counts projects by status and the milestones complete', () => {
    const summary = overview(projects, options);
    expect(summary.statusCounts).toEqual({
      TODO: 1, IN_PROGRESS: 1, AWAITING_REVIEW: 0, COMPLETE: 1,
    });
    expect(summary.milestones).toEqual({ total: 5, complete: 2 });
  });

  it('finds the pain points, naming what is affected', () => {
    const summary = overview(projects, options);
    expect(issue(summary, 'overdueMilestones').items.map((item) => [item.name, item.detail]))
      .toEqual([['Apollo: Build', '5 days overdue']]);
    expect(issue(summary, 'overdueProjects').items.map((item) => item.name)).toEqual(['Gemini']);
    expect(issue(summary, 'blocked').items[0].detail).toBe('Blocked by Apollo');
    expect(issue(summary, 'stalledReviews').items[0].detail).toBe('waiting 14 days');
    expect(issue(summary, 'unassigned').items.map((item) => item.name)).toEqual(['Apollo: Test']);
    expect(issue(summary, 'overHours').items[0].detail).toBe('120% of expected hours');
    expect(issue(summary, 'overBudget').items[0].detail).toBe('150% of budget spent');
    // Completed projects are not pain points.
    expect(summary.issues.flatMap((found) => found.items).some((item) => item.projectId === 'Pilot')).toBe(false);
  });

  it('leaves hours estimates and money out for employees', () => {
    const summary = overview(projects, { ...options, money: false });
    expect(issue(summary, 'overHours')).toBeUndefined();
    expect(issue(summary, 'overBudget')).toBeUndefined();
  });

  it('lists unfinished milestones due in the next 30 days, soonest first', () => {
    const summary = overview(projects, options);
    expect(summary.dueSoon.map(({ milestone, inDays }) => [milestone.name, inDays])).toEqual([['Test', 5]]);
  });

  it('reports nothing for an empty list', () => {
    const summary = overview([], options);
    expect(summary.issues).toEqual([]);
    expect(summary.milestones).toEqual({ total: 0, complete: 0 });
  });
});

describe('employeeOverview', () => {
  const projects = [
    project('Apollo', {
      hoursByMilestone: { Build: { e1: 3 }, Spec: { e1: 2 } },
      milestones: [
        milestone('Spec', { status: 'COMPLETE', expectedHours: 4 }),
        milestone('Build', { expectedHours: 12, assignedEmployees: ['e1', 'e2'], endDate: '2026-10-10', dependsOn: ['Design'] }),
        milestone('Design', { assignedEmployees: ['e2'] }),
        milestone('Review', { status: 'AWAITING_REVIEW', statusChangedAt: '2026-10-01T09:00:00Z', endDate: '2026-10-25' }),
      ],
    }),
  ];

  it('measures their logged hours against their share of their open milestones', () => {
    const summary = employeeOverview(projects, { today: '2026-10-15', thresholds: options.thresholds, userId: 'e1' });
    // Build's 12 hours are split with e2; Review has no estimate; Spec is complete.
    expect(summary.workload).toEqual({
      hours: 6, logged: 3, milestones: 2, unestimated: 1,
    });
    expect(summary.complete).toBe(1);
    expect(summary.milestones.map(({ milestone, hours, share }) => [milestone.name, hours, share]))
      .toEqual([['Build', 3, 6], ['Review', 0, undefined], ['Spec', 2, undefined]]);
  });

  it('lists only their own pain points and deadlines', () => {
    const summary = employeeOverview(projects, { today: '2026-10-15', thresholds: options.thresholds, userId: 'e1' });
    expect(summary.issues.map((found) => [found.kind, found.items.map((item) => item.detail)])).toEqual([
      ['overdueMilestones', ['5 days overdue']],
      ['blockedMilestones', ['waiting on Design']],
      ['stalledReviews', ['waiting 14 days']],
    ]);
    expect(summary.dueSoon.map(({ milestone }) => milestone.name)).toEqual(['Review']);
  });
});
