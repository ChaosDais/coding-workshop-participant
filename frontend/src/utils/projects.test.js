import { describe, expect, it } from 'vitest';
import {
  availability, blockingMilestones, openMilestoneByEmployee, projectRows, sortProjects, workloadByEmployee,
} from './projects.js';

const projects = new Map([
  ['design', { name: 'Design', status: 'COMPLETE' }],
  ['build', { name: 'Build', status: 'IN_PROGRESS' }],
]);

describe('availability', () => {
  it('is available with no dependencies or only complete ones', () => {
    expect(availability({ status: 'TODO', dependsOn: [] }, projects))
      .toEqual({ state: 'available', blockedBy: [], label: 'Available' });
    expect(availability({ status: 'TODO', dependsOn: ['design'] }, projects).state).toBe('available');
  });

  it('names the dependencies that are not complete, including unknown ones', () => {
    expect(availability({ status: 'TODO', dependsOn: ['design', 'build', 'abcdef123456'] }, projects)).toEqual({
      state: 'blocked',
      blockedBy: ['Build', 'an unknown project'],
      label: 'Blocked by Build, an unknown project',
    });
  });

  it('is completed for a complete project, whatever its dependencies', () => {
    expect(availability({ status: 'COMPLETE', dependsOn: ['build'] }, projects).label).toBe('Completed');
  });
});

describe('projectRows', () => {
  const project = (id, name, status, startDate, extra = {}) => ({
    id, name, status, startDate, manager: 'm1', description: '', ...extra,
  });
  const active = [
    project('done', 'Archive site', 'COMPLETE', '2026-01-01'),
    project('late', 'Zephyr', 'IN_PROGRESS', '2026-03-01'),
    project('early', 'Apollo', 'TODO', '2026-02-01', { manager: 'm2', description: 'Lunar landing' }),
  ];
  const archived = [
    project('old', 'Mercury', 'IN_PROGRESS', '2025-01-01'),
    project('old-done', 'Gemini', 'COMPLETE', '2025-01-01'),
  ];
  const users = new Map([['m1', { name: 'Morgan' }], ['m2', { name: 'Riley' }]]);
  const ids = (rows) => rows.map((row) => row.project.id);

  it('hides completed and archived projects by default and orders the rest by start date', () => {
    expect(ids(projectRows(active, archived, {}, users))).toEqual(['early', 'late']);
  });

  it('puts completed projects last when shown, after archived open ones', () => {
    expect(ids(projectRows(active, archived, { showCompleted: true, showArchived: true }, users)))
      .toEqual(['early', 'late', 'old', 'done', 'old-done']);
    const row = projectRows(active, archived, { showCompleted: true }, users).at(-1);
    expect(row).toMatchObject({ completed: true, archived: false });
  });

  it('shows completed projects when the status filter asks for them', () => {
    expect(ids(projectRows(active, archived, { status: 'COMPLETE' }, users))).toEqual(['done']);
    expect(ids(projectRows(active, archived, { status: 'TODO', showCompleted: true }, users))).toEqual(['early']);
  });

  it('searches names, descriptions and manager names, and filters by manager', () => {
    expect(ids(projectRows(active, archived, { search: 'LUNAR' }, users))).toEqual(['early']);
    expect(ids(projectRows(active, archived, { search: 'riley' }, users))).toEqual(['early']);
    expect(ids(projectRows(active, archived, { managerId: 'm1' }, users))).toEqual(['late']);
  });
});

describe('blockingMilestones', () => {
  it('lists the incomplete milestones a milestone waits for', () => {
    const milestones = [
      { id: 'a', name: 'A', status: 'COMPLETE' },
      { id: 'b', name: 'B', status: 'TODO' },
    ];
    expect(blockingMilestones({ dependsOn: ['a', 'b'] }, milestones)).toEqual(['B']);
  });
});

describe('sortProjects', () => {
  it('orders by start date, then name, without changing the input', () => {
    const input = [
      { name: 'B', startDate: '2026-02-01' }, { name: 'A', startDate: '2026-02-01' },
      { name: 'C', startDate: '2026-01-01' },
    ];
    expect(sortProjects(input).map((project) => project.name)).toEqual(['C', 'A', 'B']);
    expect(input[0].name).toBe('B');
  });
});

describe('openMilestoneByEmployee', () => {
  it('maps each employee to the unfinished milestone they work on, leaving one milestone out', () => {
    const project = {
      milestones: [
        { id: 'a', name: 'Design', status: 'IN_PROGRESS', assignedEmployees: ['e1'] },
        { id: 'b', name: 'Build', status: 'TODO', assignedEmployees: ['e2'] },
        { id: 'c', name: 'Pilot', status: 'COMPLETE', assignedEmployees: ['e3'] },
      ],
    };
    expect([...openMilestoneByEmployee(project)]).toEqual([['e1', 'Design'], ['e2', 'Build']]);
    expect([...openMilestoneByEmployee(project, 'a')]).toEqual([['e2', 'Build']]);
  });
});

describe('workloadByEmployee', () => {
  const milestone = (id, status, expectedHours, assignedEmployees) => ({
    id, status, expectedHours, assignedEmployees,
  });

  it('splits each unfinished milestone\'s expected hours between the people assigned to it', () => {
    const workloads = workloadByEmployee([
      {
        status: 'IN_PROGRESS',
        milestones: [
          milestone('m1', 'IN_PROGRESS', 30, ['ana', 'ben', 'cy']),
          milestone('m2', 'TODO', 8, ['ana']),
          milestone('m3', 'AWAITING_REVIEW', 4, ['ben', 'cy']),
          milestone('m4', 'COMPLETE', 100, ['ana']),
          milestone('m5', 'TODO', undefined, ['ana']),
        ],
      },
      // Milestones of a complete project no longer count.
      { status: 'COMPLETE', milestones: [milestone('m6', 'TODO', 50, ['ana'])] },
    ]);
    const ana = workloads.get('ana');
    expect(ana.hours).toBe(18);
    expect(ana.milestones).toBe(3);
    expect(ana.unestimated).toBe(1);
    expect([...ana.shares]).toEqual([['m1', 10], ['m2', 8]]);
    expect(workloads.get('ben').hours).toBe(12);
    expect(workloads.has('nobody')).toBe(false);
  });
});
