// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import {
  cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const manager = {
  id: 'm1', name: 'Morgan Manager', email: 'morgan@acme.com', role: 'MANAGER', archived: false,
  passwordChangeRequired: false,
};
const team = [
  { id: 'e1', name: 'Eli Employee', email: 'eli@acme.com', role: 'EMPLOYEE', managerId: 'm1', jobType: 'Developer', archived: false },
  { id: 'e2', name: 'Dana Designer', email: 'dana@acme.com', role: 'EMPLOYEE', managerId: 'm1', jobType: 'Designer', archived: false },
  { id: 'e3', name: 'Sam Support', email: 'sam@acme.com', role: 'EMPLOYEE', managerId: 'm1', archived: false },
];
const milestone = (id, name, extra = {}) => ({
  id, name, status: 'IN_PROGRESS', startDate: '2026-10-01', endDate: '2026-11-01', progressTime: 2,
  assignedEmployees: [], dependsOn: [], statusChangedAt: '2026-10-10T12:00:00Z', ...extra,
});
const apollo = {
  id: 'p1', name: 'Apollo', description: 'Crewed lunar landing', status: 'IN_PROGRESS', manager: 'm1',
  startDate: '2026-01-01', endDate: '2026-12-31', progressTime: 45, expectedHours: 40, budget: 5000, spent: 900,
  statusChangedAt: '2026-10-12T12:00:00Z', dependsOn: ['other'],
  dependencies: [{ id: 'other', name: 'Gemini (other manager)', status: 'COMPLETE', archived: false }],
  milestones: [
    milestone('ms1', 'Design', { assignedEmployees: ['e1'], expectedHours: 10 }),
    milestone('ms2', 'Build', {
      status: 'AWAITING_REVIEW', statusChangedAt: '2026-10-01T12:00:00Z', assignedEmployees: ['e2'],
    }),
  ],
};
const finished = {
  ...apollo, id: 'p2', name: 'Pilot', status: 'COMPLETE', completedAt: '2026-06-30T15:00:00Z', dependsOn: [],
  dependencies: [], milestones: [], expectedHours: undefined,
};

let calls;

beforeEach(() => {
  vi.useFakeTimers({ now: Date.parse('2026-10-14T12:00:00Z'), toFake: ['Date'] });
  setSession({ accessToken: 'a', refreshToken: 'r', user: manager });
  calls = [];
  const record = (name) => (body) => {
    calls.push({ name, body });
    return { status: name === 'unassign' ? 204 : 200, body: name === 'unassign' ? undefined : {} };
  };
  fakeBackend({
    'GET /projects': [apollo, finished],
    'GET /users': team,
    'GET /users/e1/time': {
      userId: 'e1',
      totalHours: 0,
      projects: [{
        projectId: 'p1', projectName: 'Apollo', status: 'IN_PROGRESS', archived: false, hours: 0,
        milestones: [{
          milestoneId: 'ms1', milestoneName: 'Design', status: 'IN_PROGRESS', archived: false, assigned: true,
          hours: 0, entries: 0, lastLoggedAt: null,
        }],
      }],
    },
    'GET /projects/p1/milestones?archived=true': [],
    'POST /projects/p1/milestones/ms1/assignees': record('assign'),
    'DELETE /projects/p1/milestones/ms1/assignees/e1': record('unassign'),
    'POST /projects/p1/milestones/ms1/assignees/e1/move': record('move'),
  });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  window.location.hash = '';
});

describe('manager view', () => {
  it('offers Projects and Employees only', async () => {
    window.location.hash = '#/projects';
    render(<App />);
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect([...nav.querySelectorAll('a')].map((link) => link.textContent)).toEqual(['Projects', 'Employees', 'Workload']);
    expect(await screen.findByRole('heading', { name: 'My Projects' })).toBeTruthy();
  });

  it('lists their projects with completion against expected hours and flags overruns', async () => {
    window.location.hash = '#/projects';
    render(<App />);
    const row = (await screen.findByRole('link', { name: 'Open Apollo' })).closest('tr');
    expect(within(row).getByText('45 h of 40 h')).toBeTruthy();
    expect(within(row).getByText('40 h')).toBeTruthy();
    expect(within(row).getByText('113%')).toBeTruthy();
    expect(within(row).getByText('Over by 5 h')).toBeTruthy();
    // The days in a status show once the project is opened, not in the row.
    expect(within(row).queryByText(/days in/)).toBeNull();
    await userEvent.click(within(row).getByRole('button', { name: 'Apollo' }));
    expect(screen.getByText('2 days in IN PROGRESS')).toBeTruthy();
    expect(screen.getByText('⚠ Waiting for review for 13 days')).toBeTruthy();
    // A dependency on another manager's project still shows as complete.
    expect(within(row).getByText('Available')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'New Project' })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Manager' })).toBeNull();
  });

  it('shows previously completed projects with their completion dates on request', async () => {
    window.location.hash = '#/projects';
    render(<App />);
    await screen.findByRole('link', { name: 'Open Apollo' });
    expect(screen.queryByText('Pilot')).toBeNull();
    await userEvent.click(screen.getByLabelText('Show completed projects'));
    const pilot = screen.getByRole('link', { name: 'Open Pilot' }).closest('tr');
    expect(within(pilot).getByText(/Jun 30, 2026/)).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'Completed' })).toBeTruthy();
  });

  it('assigns, moves and removes team members on a milestone, and flags a stalled review', async () => {
    window.location.hash = '#/projects/p1';
    render(<App />);
    const design = (await screen.findByText('Design', { selector: 'strong' })).closest('tr');
    expect(screen.queryByRole('heading', { name: 'Project history' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit Project' })).toBeNull();
    expect(screen.getByText('$900.00')).toBeTruthy();

    // Dana already works on Build, so she can only be moved, not added here as well.
    const assign = within(design).getByLabelText('Assign an employee to Design');
    expect(within(assign).getByRole('option', { name: 'Dana Designer — already on Build' }).disabled).toBe(true);
    await userEvent.selectOptions(assign, 'e3');
    expect(calls).toContainEqual({ name: 'assign', body: { employeeId: 'e3' } });

    await userEvent.selectOptions(within(design).getByLabelText('Move Eli Employee to another milestone'), 'ms2');
    expect(calls).toContainEqual({ name: 'move', body: { toMilestoneId: 'ms2' } });

    await userEvent.click(within(design).getByRole('button', { name: 'Remove Eli Employee from Design' }));
    expect(calls.map((call) => call.name)).toContain('unassign');

    const build = screen.getByText('Build', { selector: 'strong' }).closest('tr');
    expect(within(build).getByText('⚠ Waiting for review for 13 days')).toBeTruthy();
    // Managers log their own time too.
    const time = screen.getByRole('group', { name: 'Time on Design' });
    expect(within(time).getByRole('button', { name: 'Log Time' })).toBeTruthy();
    expect(within(time).getByRole('button', { name: 'Start Timer' })).toBeTruthy();
  });

  it('shows their team with each person’s current milestones', async () => {
    window.location.hash = '#/employees';
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'My Team' })).toBeTruthy();
    const eli = screen.getByText('Eli Employee').closest('tr');
    expect(eli.textContent).toContain('eli@acme.com');
    expect(eli.textContent).toContain('Developer');
    expect(eli.textContent).toContain('Apollo · Design');
    expect(eli.textContent).toContain('10 h across 1 milestone');
    expect(screen.getByText('Sam Support').closest('tr').textContent).toContain('Not assigned');
    expect(screen.queryByText(/salary/i)).toBeNull();

    await userEvent.click(within(eli).getByRole('button', { name: 'Eli Employee' }));
    const apollo = await screen.findByRole('region', { name: 'Apollo' });
    expect(within(apollo).getByText('Design').closest('tr').textContent).toContain('0 min');
  });
});
