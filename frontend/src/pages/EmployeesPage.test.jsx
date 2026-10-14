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
