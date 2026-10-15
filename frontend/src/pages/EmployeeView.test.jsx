// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import {
  act, cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const employee = {
  id: 'e1', name: 'Eli Employee', email: 'eli@acme.com', role: 'EMPLOYEE', managerId: 'm1', jobType: 'Developer',
  archived: false, passwordChangeRequired: false,
};
const milestone = (id, name, extra = {}) => ({
  id, name, status: 'IN_PROGRESS', startDate: '2026-10-01', endDate: '2026-11-01', progressTime: 2,
  assignedEmployees: [], dependsOn: [], statusChangedAt: '2026-09-01T12:00:00Z', ...extra,
});
// Employees get no budget or spending in project responses.
const apollo = {
  id: 'p1', name: 'Apollo', description: 'Crewed lunar landing', status: 'IN_PROGRESS', manager: 'm1',
  startDate: '2026-01-01', endDate: '2026-12-31', progressTime: 50, expectedHours: 40, dependsOn: [], dependencies: [],
  statusChangedAt: '2026-01-01T12:00:00Z',
  milestones: [
    milestone('ms1', 'Design', { assignedEmployees: ['e1'], expectedHours: 1 }),
    milestone('ms2', 'Build', { assignedEmployees: ['e2'] }),
  ],
};

let calls;
let running;
let user;

beforeEach(() => {
  // The clock also moves with real time, so Testing Library's waiting still works; tests jump it on purpose.
  vi.useFakeTimers({ now: Date.parse('2026-10-14T12:00:00Z'), shouldAdvanceTime: true });
  user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  setSession({ accessToken: 'a', refreshToken: 'r', user: employee });
  calls = [];
  running = undefined;
  fakeBackend({
    'GET /projects': [apollo],
    'GET /projects/p1': apollo,
    'GET /users': [
      employee,
      { id: 'm1', name: 'Morgan Manager', role: 'MANAGER', archived: false },
      { id: 'e2', name: 'Dana Designer', role: 'EMPLOYEE', managerId: 'm1', archived: false },
    ],
    'GET /timer': () => (running ? { status: 200, body: running } : { status: 404, body: { error: 'No timer is running' } }),
    'POST /projects/p1/milestones/ms1/time': (body) => {
      calls.push({ name: 'log', body });
      return { status: 201, body: { id: 't1', hours: body.hours } };
    },
    'POST /projects/p1/milestones/ms1/timer': () => {
      running = {
        id: 't2', projectId: 'p1', milestoneId: 'ms1', source: 'TIMER', startedAt: new Date().toISOString(),
      };
      calls.push({ name: 'start' });
      return { status: 201, body: running };
    },
    'POST /timer/stop': () => {
      calls.push({ name: 'stop' });
      running = undefined;
      return { status: 200, body: { id: 't2', hours: 0.01 } };
    },
    'PUT /projects/p1/milestones/ms1/status': (body) => {
      calls.push({ name: 'status', body });
      return { status: 200, body: {} };
    },
    'GET /projects/p1/milestones/ms1/time': [{
      id: 't0', userId: 'e1', source: 'MANUAL', hours: 1.5, note: 'Wireframes', loggedAt: '2026-10-13T09:00:00Z',
    }],
  });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  window.location.hash = '';
});

describe('employee view', () => {
  it('lists only their current projects, with their milestones and no warnings', async () => {
    window.location.hash = '#/projects';
    render(<App />);
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect([...nav.querySelectorAll('a')].map((link) => link.textContent)).toEqual(['Projects']);
    expect((await screen.findByText('Your workload:')).closest('p').textContent).toBe('Your workload: 1 h across 1 milestone');
    const row = (await screen.findByRole('link', { name: 'Open Apollo' })).closest('tr');
    expect(within(row).getByText(/Design/)).toBeTruthy();
    expect(within(row).queryByText(/Build/)).toBeNull();
    expect(within(row).getByText('125%')).toBeTruthy();
    expect(within(row).queryByText(/Over by/)).toBeNull();
    expect(within(row).queryByText(/days in/)).toBeNull();
    expect(screen.queryByLabelText('Show completed projects')).toBeNull();
  });

  it('shows the project without money and lets them work only on their own milestone', async () => {
    window.location.hash = '#/projects/p1';
    render(<App />);
    const design = (await screen.findByText('Design', { selector: 'strong' })).closest('tr');
    expect(screen.queryByText('Budget')).toBeNull();
    expect(screen.queryByText('Spent')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add Milestone' })).toBeNull();
    expect(within(design).getByText('You')).toBeTruthy();
    expect(within(design).queryByRole('combobox')).toBeNull();

    // Time controls sit on their own line, only under their own milestone.
    expect(screen.getByRole('group', { name: 'Time on Design' })).toBeTruthy();
    expect(screen.queryByRole('group', { name: 'Time on Build' })).toBeNull();

    await user.click(within(design).getByRole('button', { name: 'Send for Review' }));
    expect(calls).toContainEqual({ name: 'status', body: { status: 'AWAITING_REVIEW' } });

    await user.click(within(screen.getByRole('group', { name: 'Time on Design' })).getByRole('button', { name: 'Time Log' }));
    expect(await screen.findByText('Wireframes')).toBeTruthy();
  });

  it('logs hours by hand after checking them', async () => {
    window.location.hash = '#/projects/p1';
    render(<App />);
    const time = await screen.findByRole('group', { name: 'Time on Design' });
    await user.click(within(time).getByRole('button', { name: 'Log Time' }));
    const dialog = screen.getByRole('dialog', { name: 'Log Time on Design' });

    await user.type(within(dialog).getByLabelText(/^Hours worked/), '30');
    await user.click(within(dialog).getByRole('button', { name: 'Log Time' }));
    expect(within(dialog).getByText('Enter the hours worked: more than 0 and at most 24')).toBeTruthy();

    await user.clear(within(dialog).getByLabelText(/^Hours worked/));
    await user.type(within(dialog).getByLabelText(/^Hours worked/), '1.5');
    await user.type(within(dialog).getByLabelText('Note'), 'Wireframes');
    await user.click(within(dialog).getByRole('button', { name: 'Log Time' }));
    expect(calls).toContainEqual({ name: 'log', body: { hours: 1.5, note: 'Wireframes' } });
  });

  it('runs a timer on the milestone\'s own line that ticks every second, and logs the time when stopped', async () => {
    window.location.hash = '#/projects/p1';
    render(<App />);
    const time = await screen.findByRole('group', { name: 'Time on Design' });
    await user.click(within(time).getByRole('button', { name: 'Start Timer' }));

    // On the timer's own project the timer shows on its milestone, not in a bar at the top.
    const clock = await within(time).findByLabelText('Time on the timer');
    expect(screen.queryByRole('region', { name: 'Timer' })).toBeNull();
    expect(clock.textContent).toMatch(/^0:00:0[01]$/);
    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(clock.textContent).toMatch(/^0:00:0[34]$/);

    await user.click(within(time).getByRole('button', { name: 'Stop Timer' }));
    expect(calls.map((call) => call.name)).toEqual(['start', 'stop']);
    expect((await within(time).findByRole('status')).textContent).toContain('Logged 1 min on Design');
  });

  it('shows a running timer in the bar at the top on other pages', async () => {
    window.location.hash = '#/projects/p1';
    render(<App />);
    const time = await screen.findByRole('group', { name: 'Time on Design' });
    await user.click(within(time).getByRole('button', { name: 'Start Timer' }));
    await within(time).findByLabelText('Time on the timer');

    await act(async () => { window.location.hash = '#/projects'; });
    const bar = await screen.findByRole('region', { name: 'Timer' });
    expect(within(bar).getByText('Design · Apollo')).toBeTruthy();
    await user.click(within(bar).getByRole('button', { name: 'Stop Timer' }));
    expect((await within(bar).findByRole('status')).textContent).toContain('Logged 1 min on Design');
  });
});
