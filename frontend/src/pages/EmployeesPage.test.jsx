// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it,
} from 'vitest';
import {
  cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider } from '../auth/AuthContext.jsx';
import EmployeesPage from './EmployeesPage.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const people = [
  { id: 'admin-1', name: 'Ada Admin', email: 'admin@acme.com', role: 'ADMIN', archived: false },
  { id: 'manager-1', name: 'Morgan Manager', email: 'morgan@acme.com', role: 'MANAGER', archived: false, salary: 98000 },
  {
    id: 'employee-1', name: 'Eli Employee', email: 'eli@acme.com', role: 'EMPLOYEE', archived: false,
    managerId: 'manager-1', jobType: 'Software Engineer', salary: 72000.5,
  },
];

let backend;
let resetBody;

beforeEach(() => {
  setSession({ accessToken: 'a', refreshToken: 'r', user: people[0] });
  resetBody = undefined;
  backend = fakeBackend({
    'PUT /users/employee-1/password': (body) => {
      resetBody = body;
      return { status: 204, body: undefined };
    },
    'GET /users': people,
    'GET /projects': [
      {
        id: 'p1', name: 'Apollo', status: 'IN_PROGRESS', manager: 'manager-1', startDate: '2026-01-01', endDate: '2026-12-31',
        progressTime: 3, dependsOn: [],
        milestones: [{
          id: 'ms1', name: 'Design', status: 'IN_PROGRESS', progressTime: 3, expectedHours: 12, assignedEmployees: ['employee-1'], dependsOn: [],
        }],
      },
      {
        id: 'p3', name: 'Mercury', status: 'COMPLETE', manager: 'manager-1', startDate: '2025-01-01', endDate: '2025-12-31',
        progressTime: 9, dependsOn: [], milestones: [],
      },
      {
        id: 'p2', name: 'Gemini', status: 'TODO', manager: 'someone-else', startDate: '2026-01-01', endDate: '2026-12-31',
        progressTime: 0, dependsOn: [], milestones: [],
      },
    ],
    'GET /users/employee-1/time': {
      userId: 'employee-1',
      totalHours: 3.5,
      projects: [{
        projectId: 'p1', projectName: 'Apollo', status: 'IN_PROGRESS', archived: false, hours: 3.5,
        milestones: [
          {
            milestoneId: 'ms1', milestoneName: 'Design', status: 'IN_PROGRESS', archived: false, assigned: true,
            hours: 3.25, entries: 2, lastLoggedAt: '2026-10-13T15:00:00Z',
          },
          {
            milestoneId: 'ms0', milestoneName: 'Research', status: 'COMPLETE', archived: true, assigned: false,
            hours: 0.25, entries: 1, lastLoggedAt: '2026-09-01T15:00:00Z',
          },
        ],
      }, {
        projectId: 'p8', projectName: 'Finished work', status: 'COMPLETE', archived: false, hours: 5,
        milestones: [{
          milestoneId: 'ms8', milestoneName: 'Wrap-up', status: 'COMPLETE', archived: false, assigned: false,
          hours: 5, entries: 1, lastLoggedAt: '2026-08-01T15:00:00Z',
        }],
      }, {
        projectId: 'p9', projectName: 'Shelved', status: 'IN_PROGRESS', archived: true, hours: 1,
        milestones: [{
          milestoneId: 'ms9', milestoneName: 'Spike', status: 'IN_PROGRESS', archived: false, assigned: false,
          hours: 1, entries: 1, lastLoggedAt: '2026-07-01T15:00:00Z',
        }],
      }],
    },
    'GET /users?archived=true': [{ id: 'old-1', name: 'Olive Old', email: 'olive@acme.com', role: 'EMPLOYEE', archived: true }],
  });
});
afterEach(cleanup);

describe('employees page', () => {
  it('shows everyone with email, id, role, job, salary and manager', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    const eli = (await screen.findByText('Eli Employee')).closest('tr');
    expect(eli.textContent).toContain('eli@acme.com');
    expect(eli.textContent).toContain('employee');
    expect(eli.textContent).toContain('Software Engineer');
    expect(eli.textContent).toContain('$72,000.50');
    expect(eli.textContent).toContain('Morgan Manager');
    expect(screen.queryByText('Olive Old')).toBeNull();
  });

  it('searches, filters by role and shows archived people on request', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    await screen.findByText('Eli Employee');

    await userEvent.type(screen.getByLabelText('Search'), 'engineer');
    expect(screen.getAllByRole('row')).toHaveLength(2);
    await userEvent.clear(screen.getByLabelText('Search'));

    await userEvent.selectOptions(screen.getByLabelText('Role'), 'MANAGER');
    expect(screen.getByText('Morgan Manager')).toBeTruthy();
    expect(screen.queryByText('Eli Employee')).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText('Role'), '');

    await userEvent.click(screen.getByLabelText('Show archived users'));
    expect(screen.getByText('Olive Old')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Restore' })).toBeTruthy();
  });

  it('opens an employee to show their assigned milestones and the time logged on each', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    const eli = await screen.findByRole('button', { name: 'Eli Employee' });
    // Admins have nothing to open.
    expect(screen.queryByRole('button', { name: 'Ada Admin' })).toBeNull();

    await userEvent.click(eli);
    expect(eli.getAttribute('aria-expanded')).toBe('true');
    const apollo = await screen.findByRole('region', { name: 'Apollo' });
    // Completed and archived projects are left out, and so is their time.
    expect(screen.getByText('Logged on current projects:').closest('p').textContent).toBe('Logged on current projects: 3 h 30 min');
    expect(screen.queryByText('Finished work')).toBeNull();
    expect(screen.queryByText('Shelved')).toBeNull();
    const design = within(apollo).getByText('Design').closest('tr');
    expect(design.textContent).toContain('3 h 15 min');
    // Their share of the milestone's expected hours.
    expect(design.textContent).toContain('12 h');
    expect(screen.getByText('Eli Employee', { selector: 'span' }).closest('tr').textContent).toContain('12 h across 1 milestone');
    expect(design.textContent).toContain('2 entries');
    const research = within(apollo).getByText('Research').closest('tr');
    expect(research.textContent).toContain('No longer');
    expect(within(research).getByText('Archived')).toBeTruthy();

    await userEvent.click(eli);
    expect(screen.queryByRole('region', { name: 'Apollo' })).toBeNull();
  });

  it('opens a manager to show their projects with milestones and assignees, and their team', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    await userEvent.click(await screen.findByRole('button', { name: 'Morgan Manager' }));

    const projects = await screen.findByRole('region', { name: 'Projects managed by Morgan Manager' });
    const apollo = await within(projects).findByRole('region', { name: 'Apollo' });
    expect(within(projects).queryByText('Gemini')).toBeNull();
    // Completed projects are left out.
    expect(within(projects).queryByText('Mercury')).toBeNull();
    const design = within(apollo).getByText('Design').closest('tr');
    expect(design.textContent).toContain('Eli Employee');

    const team = screen.getByRole('region', { name: 'Team of Morgan Manager' });
    expect(within(team).getByText('Eli Employee')).toBeTruthy();
    expect(within(team).getByText('Software Engineer')).toBeTruthy();
    expect(within(team).getByText('Eli Employee').closest('tr').textContent).toContain('12 h across 1 milestone');
  });

  it('does not offer to archive your own account', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    const me = (await screen.findByText('Ada Admin')).closest('tr');
    expect(me.textContent).not.toContain('Archive');
  });

  it('lets an admin set someone else\u2019s password, after checking it', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    const eli = (await screen.findByText('Eli Employee')).closest('tr');
    await userEvent.click(within(eli).getByRole('button', { name: 'Reset password' }));
    const dialog = screen.getByRole('dialog', { name: 'Reset password for Eli Employee' });

    await userEvent.type(within(dialog).getByLabelText(/^New password/), 'short');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Set password' }));
    expect(within(dialog).getByText('Password must be at least 8 characters')).toBeTruthy();

    await userEvent.clear(within(dialog).getByLabelText(/^New password/));
    await userEvent.type(within(dialog).getByLabelText(/^New password/), 'a-new-password');
    await userEvent.type(within(dialog).getByLabelText(/^Confirm new password/), 'a-different-one');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Set password' }));
    expect(within(dialog).getByText('The passwords do not match')).toBeTruthy();
    expect(backend).not.toHaveBeenCalledWith(expect.stringContaining('/password'), expect.anything());

    await userEvent.clear(within(dialog).getByLabelText(/^Confirm new password/));
    await userEvent.type(within(dialog).getByLabelText(/^Confirm new password/), 'a-new-password');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Set password' }));

    expect((await screen.findByRole('status')).textContent).toContain('Eli Employee\u2019s password has been changed');
    expect(resetBody).toEqual({ password: 'a-new-password' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('does not offer a password reset for your own account or archived people', async () => {
    render(<AuthProvider><EmployeesPage /></AuthProvider>);
    const me = (await screen.findByText('Ada Admin')).closest('tr');
    expect(within(me).queryByRole('button', { name: 'Reset password' })).toBeNull();
    await userEvent.click(screen.getByLabelText('Show archived users'));
    const olive = screen.getByText('Olive Old').closest('tr');
    expect(within(olive).queryByRole('button', { name: 'Reset password' })).toBeNull();
  });
});
