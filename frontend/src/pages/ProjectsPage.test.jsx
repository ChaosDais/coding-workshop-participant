// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import {
  cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider } from '../auth/AuthContext.jsx';
import ProjectsPage from './ProjectsPage.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const manager = { id: 'm1', name: 'Morgan Manager', role: 'MANAGER', archived: false };
const project = (id, name, status, dependsOn = [], extra = {}) => ({
  id, name, status, dependsOn, manager: 'm1', startDate: '2026-01-01', endDate: '2026-12-31', progressTime: 12.5,
  budget: 1000, spent: 0, milestones: [], ...extra,
});

beforeEach(() => {
  setSession({ accessToken: 'a', refreshToken: 'r', user: { id: 'admin', name: 'Admin', role: 'ADMIN' } });
  fakeBackend({
    'GET /projects': [
      project('p1', 'Design', 'IN_PROGRESS', [], {
        description: 'Visual identity',
        hoursByMilestone: { ms1: { e1: 2.5, m1: 1 } },
        milestones: [{
          id: 'ms1', name: 'Wireframes', description: 'Low fidelity first', status: 'IN_PROGRESS', startDate: '2026-01-05',
          endDate: '2026-02-01', progressTime: 2, assignedEmployees: ['e1'], dependsOn: [],
        }],
      }),
      project('p2', 'Build', 'TODO', ['p1'], { startDate: '2026-02-01', endDate: '2026-06-30' }),
      project('p4', 'Launch prep', 'COMPLETE', [], { manager: 'm2', startDate: '2025-06-01' }),
    ],
    'GET /projects?archived=true': [project('p3', 'Pilot', 'IN_PROGRESS')],
    'GET /users': [manager, { id: 'm2', name: 'Riley Lead', role: 'MANAGER', archived: false },
      { id: 'e1', name: 'Eli Employee', role: 'EMPLOYEE', archived: false }],
    'GET /users?archived=true': [],
  });
});
afterEach(cleanup);

function renderPage() {
  return render(<AuthProvider><ProjectsPage /></AuthProvider>);
}

describe('projects page', () => {
  it('lists current projects with status, manager, progress and availability', async () => {
    renderPage();
    const build = (await screen.findByText('Build')).closest('tr');
    expect(within(build).getByText('TODO')).toBeTruthy();
    expect(within(build).getByText('Morgan Manager')).toBeTruthy();
    expect(within(build).getByText('12 h 30 min')).toBeTruthy();
    expect(within(build).getByText('Blocked by Design')).toBeTruthy();
    expect(within(screen.getByText('Design').closest('tr')).getByText('Available')).toBeTruthy();
    expect(screen.queryByText('Pilot')).toBeNull();
  });

  it('shows each project start and expected end date', async () => {
    renderPage();
    const build = (await screen.findByText('Build')).closest('tr');
    expect(within(build).getByText('Feb 1, 2026')).toBeTruthy();
    expect(within(build).getByText('Jun 30, 2026')).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'Expected end' })).toBeTruthy();
  });

  it('hides completed projects until asked, then shows them greyed out at the bottom', async () => {
    renderPage();
    await screen.findByText('Build');
    expect(screen.queryByText('Launch prep')).toBeNull();

    await userEvent.click(screen.getByLabelText('Show completed projects'));
    const rows = screen.getAllByRole('row').slice(1);
    const last = rows.at(-1);
    expect(within(last).getByText('Launch prep')).toBeTruthy();
    expect(within(last).getByText('Completed')).toBeTruthy();
    expect(last.className).toContain('completed');
  });

  it('searches and filters by status and manager', async () => {
    renderPage();
    await screen.findByText('Build');

    await userEvent.type(screen.getByLabelText('Search'), 'buil');
    expect(screen.getAllByRole('row')).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'COMPLETE');
    expect(screen.getByText('Launch prep')).toBeTruthy();
    expect(screen.queryByText('Build')).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText('Status'), '');

    await userEvent.selectOptions(screen.getByLabelText('Manager'), 'm1');
    expect(screen.getByText('Build')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText('Manager'), 'm2');
    expect(screen.getByText(/No projects match these filters/)).toBeTruthy();
  });

  it('shows archived projects on request', async () => {
    renderPage();
    await screen.findByText('Build');
    await userEvent.click(screen.getByLabelText('Show archived projects'));
    const pilot = screen.getByText('Pilot').closest('tr');
    expect(within(pilot).getByText('Archived')).toBeTruthy();
  });

  it('opens a project in place to show its description and milestones, and a milestone to show who is on it', async () => {
    renderPage();
    const toggle = await screen.findByRole('button', { name: 'Design' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByRole('link', { name: 'Open Design' }).getAttribute('href')).toBe('#/projects/p1');

    await userEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('Visual identity')).toBeTruthy();
    const milestone = screen.getByRole('button', { name: 'Wireframes' });
    expect(screen.queryByText('Low fidelity first')).toBeNull();

    await userEvent.click(milestone);
    expect(screen.getByText('Low fidelity first')).toBeTruthy();
    // Each person's time on the milestone, beside their name; the manager logged time without being assigned.
    const people = screen.getByRole('list', { name: 'Time on Wireframes' });
    const rows = within(people).getAllByRole('listitem').map((item) => item.textContent);
    expect(rows).toEqual(['Eli Employee2 h 30 min', 'Morgan Manager · not assigned1 h']);

    // Clicking anywhere on the row closes it again.
    await userEvent.click(screen.getByText('Jan 1, 2026', { selector: 'td' }));
    expect(screen.queryByText('Visual identity')).toBeNull();
  });

  it('lets admins archive projects and milestones from the list', async () => {
    const deleted = [];
    fakeBackend({
      'GET /projects': [project('p1', 'Design', 'IN_PROGRESS', [], {
        milestones: [{
          id: 'ms1', name: 'Wireframes', status: 'TODO', progressTime: 0, assignedEmployees: [], dependsOn: [],
        }],
      })],
      'GET /projects?archived=true': [],
      'GET /users': [manager],
      'GET /users?archived=true': [],
      'DELETE /projects/p1': () => { deleted.push('p1'); return { status: 204 }; },
      'DELETE /projects/p1/milestones/ms1': () => { deleted.push('ms1'); return { status: 204 }; },
    });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Design' }));
    await userEvent.click(screen.getByRole('button', { name: 'Archive milestone Wireframes' }));
    await userEvent.click(screen.getByRole('button', { name: 'Archive Design' }));
    expect(deleted).toEqual(['ms1', 'p1']);
    expect(confirm).toHaveBeenCalledWith('Archive Design? It can be restored later.');
    confirm.mockRestore();
  });

  it('validates a new project before creating it', async () => {
    renderPage();
    await screen.findByText('Build');
    await userEvent.click(screen.getByRole('button', { name: 'New Project' }));
    const dialog = screen.getByRole('dialog', { name: 'New Project' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save project' }));

    expect(within(dialog).getByText('Name is required')).toBeTruthy();
    expect(within(dialog).getByText('Manager is required')).toBeTruthy();
    expect(within(dialog).getByText('Start date is required')).toBeTruthy();
  });
});
