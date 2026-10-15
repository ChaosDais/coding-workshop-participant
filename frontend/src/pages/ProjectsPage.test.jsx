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

/** Lookups inside the project table, since project names also appear in the overview above it. */
const inTable = () => within(screen.getByRole('table', { name: 'Projects' }));

function renderPage() {
  return render(<AuthProvider><ProjectsPage /></AuthProvider>);
}

describe('projects page', () => {
  it('lists current projects with status, manager, progress and availability', async () => {
    renderPage();
    const build = (await within(await screen.findByRole('table')).findByText('Build')).closest('tr');
    expect(within(build).getByText('TODO')).toBeTruthy();
    expect(within(build).getByText('Morgan Manager')).toBeTruthy();
    expect(within(build).getByText('12 h 30 min')).toBeTruthy();
    expect(within(build).getByText('Blocked by Design')).toBeTruthy();
    expect(within(inTable().getByText('Design').closest('tr')).getByText('Available')).toBeTruthy();
    expect(inTable().queryByText('Pilot')).toBeNull();
  });

  it('opens with an overview of status and pain points for the projects shown', async () => {
    renderPage();
    const summary = await screen.findByRole('region', { name: 'Overview' });
    expect(within(summary).getByRole('heading', { name: 'Project status' })).toBeTruthy();
    expect(within(summary).getByRole('heading', { name: 'Needs attention' })).toBeTruthy();
    // Build waits on Design, which is not complete, so it is listed as blocked once past its start date.
    expect(within(summary).getByText(/blocked project/)).toBeTruthy();
    expect(within(summary).getByRole('img', { name: 'COMPLETE: 1 project' })).toBeTruthy();
  });

  it('shows each project start and expected end date', async () => {
    renderPage();
    const build = (await within(await screen.findByRole('table')).findByText('Build')).closest('tr');
    // One column for both dates, the year written once.
    expect(within(build).getByText('Feb 1 – Jun 30, 2026')).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'Dates' })).toBeTruthy();
  });

  it('hides completed projects until asked, then shows them in pale green at the bottom', async () => {
    renderPage();
    await screen.findByRole('table');
    expect(inTable().queryByText('Launch prep')).toBeNull();

    await userEvent.click(screen.getByLabelText('Show completed projects'));
    const rows = screen.getAllByRole('row').slice(1);
    const last = rows.at(-1);
    expect(within(last).getByText('Launch prep')).toBeTruthy();
    expect(within(last).getByText('Completed')).toBeTruthy();
    expect(last.className).toContain('completed');
  });

  it('searches and filters by status and manager', async () => {
    renderPage();
    await screen.findByRole('table');

    await userEvent.type(screen.getByLabelText('Search'), 'buil');
    expect(screen.getAllByRole('row')).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'COMPLETE');
    expect(inTable().getByText('Launch prep')).toBeTruthy();
    expect(inTable().queryByText('Build')).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText('Status'), '');

    await userEvent.selectOptions(screen.getByLabelText('Manager'), 'm1');
    expect(inTable().getByText('Build')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText('Manager'), 'm2');
    expect(screen.getByText(/No projects match these filters/)).toBeTruthy();
  });

  it('shows archived projects on request', async () => {
    renderPage();
    await screen.findByRole('table');
    await userEvent.click(screen.getByLabelText('Show archived projects'));
    const pilot = inTable().getByText('Pilot').closest('tr');
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
    await userEvent.click(screen.getAllByText('Jan 1 – Dec 31, 2026', { selector: 'td' })[0]);
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

  it('shows projects in pages of 10, 50 or 100, starting again from page 1 when the filters change', async () => {
    const many = Array.from({ length: 23 }, (_, index) => project(`p${index + 1}`, `Project ${String(index + 1).padStart(2, '0')}`, 'IN_PROGRESS'));
    fakeBackend({
      'GET /projects': many, 'GET /projects?archived=true': [], 'GET /users': [manager], 'GET /users?archived=true': [],
    });
    renderPage();
    await screen.findByRole('table');
    const names = () => inTable().getAllByRole('button', { name: /^Project \d+$/ }).map((button) => button.textContent);
    expect(screen.getByText('Showing 1–10 of 23 projects')).toBeTruthy();
    expect(names()).toHaveLength(10);

    const top = () => screen.getByRole('navigation', { name: 'Project pages, top' });
    await userEvent.click(within(top()).getByRole('button', { name: 'Last page' }));
    expect(screen.getByText('Showing 21–23 of 23 projects')).toBeTruthy();
    expect(names()).toEqual(['Project 21', 'Project 22', 'Project 23']);
    expect(screen.getByRole('navigation', { name: 'Project pages, bottom' })).toBeTruthy();

    await userEvent.type(screen.getByLabelText('Search'), '2');
    expect(screen.getByText(/^Showing 1–/)).toBeTruthy();

    await userEvent.clear(screen.getByLabelText('Search'));
    await userEvent.selectOptions(screen.getByLabelText('Per page'), '50');
    expect(names()).toHaveLength(23);
    expect(screen.queryByRole('navigation', { name: 'Project pages, top' })).toBeNull();
  });

  it('puts projects awaiting review in their own section above the list', async () => {
    fakeBackend({
      'GET /projects': [
        project('p1', 'Design', 'IN_PROGRESS'),
        project('p5', 'Rollout', 'AWAITING_REVIEW'),
        project('p6', 'Docs', 'AWAITING_REVIEW'),
      ],
      'GET /projects?archived=true': [],
      'GET /users': [manager],
      'GET /users?archived=true': [],
    });
    renderPage();
    const review = await screen.findByRole('region', { name: /Awaiting review/ });
    const reviewTable = within(review).getByRole('table', { name: 'Projects awaiting review' });
    expect(within(reviewTable).getAllByRole('button', { name: /^(Rollout|Docs)$/ })).toHaveLength(2);
    expect(within(review).getByRole('heading').textContent).toBe('Awaiting review2');

    // Not repeated in the main list, which counts only the rest.
    const main = screen.getByRole('table', { name: 'Projects' });
    expect(within(main).queryByText('Rollout')).toBeNull();
    expect(within(main).getByText('Design')).toBeTruthy();
    expect(screen.getByText('Showing 1–1 of 1 project')).toBeTruthy();

    // The section follows the search like the list does.
    await userEvent.type(screen.getByLabelText('Search'), 'roll');
    expect(within(screen.getByRole('table', { name: 'Projects awaiting review' })).queryByText('Docs')).toBeNull();
  });

  it('keeps its search and filters when you come back to it', async () => {
    const first = renderPage();
    await screen.findByRole('table');
    await userEvent.type(screen.getByLabelText('Search'), 'buil');
    await userEvent.selectOptions(screen.getByLabelText('Per page'), '50');
    first.unmount();

    renderPage();
    await screen.findByRole('table');
    expect(screen.getByLabelText('Search').value).toBe('buil');
    expect(screen.getByLabelText('Per page').value).toBe('50');
    expect(inTable().queryByText('Design')).toBeNull();
  });

  it('validates a new project before creating it', async () => {
    renderPage();
    await screen.findByRole('table');
    await userEvent.click(screen.getByRole('button', { name: 'New Project' }));
    const dialog = screen.getByRole('dialog', { name: 'New Project' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save project' }));

    expect(within(dialog).getByText('Name is required')).toBeTruthy();
    expect(within(dialog).getByText('Manager is required')).toBeTruthy();
    expect(within(dialog).getByText('Start date is required')).toBeTruthy();
  });
});
