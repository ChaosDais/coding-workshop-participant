// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import {
  cleanup, fireEvent, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider } from '../auth/AuthContext.jsx';
import WorkloadPage from './WorkloadPage.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const people = [
  { id: 'm1', name: 'Morgan Manager', role: 'MANAGER', archived: false },
  { id: 'm2', name: 'Riley Lead', role: 'MANAGER', archived: false },
  { id: 'e1', name: 'Eli Employee', role: 'EMPLOYEE', managerId: 'm1', jobType: 'Developer', archived: false },
  { id: 'e2', name: 'Dana Designer', role: 'EMPLOYEE', managerId: 'm1', archived: false },
  { id: 'e3', name: 'Olu Other', role: 'EMPLOYEE', managerId: 'm2', archived: false },
];
const milestone = (id, expectedHours, assignedEmployees) => ({
  id, name: id, status: 'IN_PROGRESS', expectedHours, assignedEmployees, dependsOn: [], progressTime: 0,
});
const projects = [{
  id: 'p1', name: 'Apollo', status: 'IN_PROGRESS', manager: 'm1', dependsOn: [], milestones: [
    // Eli and Dana share 20 hours; Eli has 10 more alone.
    milestone('ms1', 20, ['e1', 'e2']), milestone('ms2', 10, ['e1']),
  ],
}, {
  id: 'p2', name: 'Gemini', status: 'IN_PROGRESS', manager: 'm2', dependsOn: [], milestones: [milestone('ms3', 8, ['e3'])],
}];
const daily = [
  { userId: 'e1', projectId: 'p1', milestoneId: 'ms1', day: '2026-10-01', hours: 6 },
  { userId: 'e1', projectId: 'p1', milestoneId: 'ms2', day: '2026-10-14', hours: 4 },
  { userId: 'e2', projectId: 'p1', milestoneId: 'ms1', day: '2026-10-15', hours: 12 },
];

function renderAs(user) {
  setSession({ accessToken: 'a', refreshToken: 'r', user });
  return render(<AuthProvider><WorkloadPage /></AuthProvider>);
}

beforeEach(() => {
  vi.useFakeTimers({ now: Date.parse('2026-10-15T12:00:00Z'), toFake: ['Date'] });
  fakeBackend({ 'GET /projects': projects, 'GET /users': people, 'GET /time/daily': daily });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('workload page', () => {
  it('charts every employee for admins, with their progress against their workload', async () => {
    renderAs({ id: 'admin', name: 'Ada', role: 'ADMIN' });
    const eli = await screen.findByRole('region', { name: 'Eli Employee' });
    expect(within(eli).getByText('10 h of 20 h')).toBeTruthy();
    expect(within(eli).getByText('50% done')).toBeTruthy();
    expect(within(eli).getByRole('img').getAttribute('aria-label')).toContain('10 h logged of a 20 h workload');

    // Over the workload is flagged with a label, not only by the line.
    const dana = screen.getByRole('region', { name: 'Dana Designer' });
    expect(within(dana).getByText('⚠ Over by 2 h')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Olu Other' })).toBeTruthy();

    // The table holds the same figures.
    const row = screen.getByRole('cell', { name: 'Eli Employee' }).closest('tr');
    expect(row.textContent).toContain('Morgan Manager');
    expect(row.textContent).toContain('10 h');
    expect(row.textContent).toContain('50%');

    await userEvent.selectOptions(screen.getByLabelText('Team'), 'm2');
    expect(screen.queryByRole('region', { name: 'Eli Employee' })).toBeNull();
    expect(screen.getByRole('region', { name: 'Olu Other' })).toBeTruthy();
  });

  it('sorts the charts by any figure, ascending or descending', async () => {
    const user = userEvent.setup();
    renderAs({ id: 'admin', name: 'Ada', role: 'ADMIN' });
    await screen.findByRole('region', { name: 'Eli Employee' });
    const order = () => [...document.querySelectorAll('.workload-card')].map((card) => card.getAttribute('aria-label'));
    const toggle = () => screen.getByRole('button', { name: /^Sort order/ });

    // Workloads: Eli 20 h (10 logged, 50%), Dana 10 h (12 logged, 120%), Olu 8 h (none logged, 0%).
    expect(order()).toEqual(['Dana Designer', 'Eli Employee', 'Olu Other']);
    await user.click(toggle());
    expect(order()).toEqual(['Olu Other', 'Eli Employee', 'Dana Designer']);

    // Picking a figure starts from its natural direction: the largest workload first.
    await user.selectOptions(screen.getByLabelText('Sort by'), 'workload');
    expect(toggle().textContent).toBe('↓ Descending');
    expect(order()).toEqual(['Eli Employee', 'Dana Designer', 'Olu Other']);
    await user.click(toggle());
    expect(order()).toEqual(['Olu Other', 'Dana Designer', 'Eli Employee']);

    await user.selectOptions(screen.getByLabelText('Sort by'), 'done');
    expect(order()).toEqual(['Olu Other', 'Eli Employee', 'Dana Designer']);
    await user.selectOptions(screen.getByLabelText('Sort by'), 'remaining');
    expect(order()).toEqual(['Eli Employee', 'Olu Other', 'Dana Designer']);
    // The summary table follows the same order.
    expect(screen.getAllByRole('row').slice(1).map((row) => row.cells[0].textContent))
      .toEqual(['Eli Employee', 'Olu Other', 'Dana Designer']);
  });

  it('shows managers only their own team', async () => {
    renderAs({ id: 'm1', name: 'Morgan Manager', role: 'MANAGER' });
    expect(await screen.findByRole('heading', { name: 'Team Workload' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Eli Employee' })).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Olu Other' })).toBeNull();
    expect(screen.queryByLabelText('Team')).toBeNull();
  });

  it('reads out the total on a day from the keyboard, as hovering does', async () => {
    renderAs({ id: 'admin', name: 'Ada', role: 'ADMIN' });
    const eli = await screen.findByRole('region', { name: 'Eli Employee' });
    const chart = within(eli).getByRole('img');
    chart.focus();
    // The first day of the last four weeks, before anything was logged.
    fireEvent.keyDown(chart, { key: 'Home' });
    expect(within(eli).getByRole('status').textContent).toBe('0 minlogged by Sep 18, 2026of 20 h workload');
    // Oct 1, 13 days later: the first 6 hours.
    for (let step = 0; step < 13; step += 1) {
      fireEvent.keyDown(chart, { key: 'ArrowRight' });
    }
    expect(within(eli).getByRole('status').textContent).toContain('6 hlogged by Oct 1, 2026');
    fireEvent.keyDown(chart, { key: 'End' });
    expect(within(eli).getByRole('status').textContent).toContain('10 h');
  });
});
