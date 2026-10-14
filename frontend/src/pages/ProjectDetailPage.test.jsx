// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it,
} from 'vitest';
import {
  cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider } from '../auth/AuthContext.jsx';
import ProjectDetailPage from './ProjectDetailPage.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const milestone = (id, name, status, extra = {}) => ({
  id, name, status, startDate: '2026-02-01', endDate: '2026-03-01', progressTime: 2, assignedEmployees: [],
  dependsOn: [], ...extra,
});
const apollo = {
  id: 'p1', name: 'Apollo', description: 'Crewed lunar landing', status: 'IN_PROGRESS', manager: 'm1',
  startDate: '2026-01-01', endDate: '2026-12-31', progressTime: 4, budget: 5000, spent: 125.5, dependsOn: ['p0'],
  milestones: [
    milestone('ms1', 'Design', 'IN_PROGRESS', { assignedEmployees: ['e1'] }),
    milestone('ms2', 'Build', 'TODO', { dependsOn: ['ms1'] }),
  ],
};
const mercury = {
  ...apollo, id: 'p0', name: 'Mercury', status: 'IN_PROGRESS', dependsOn: [], milestones: [],
};

beforeEach(() => {
  setSession({ accessToken: 'a', refreshToken: 'r', user: { id: 'admin', name: 'Admin', role: 'ADMIN' } });
  fakeBackend({
    'GET /projects': [mercury, apollo],
    'GET /projects?archived=true': [],
    'GET /users': [
      { id: 'm1', name: 'Morgan Manager', role: 'MANAGER', archived: false },
      { id: 'e1', name: 'Eli Employee', role: 'EMPLOYEE', managerId: 'm1', archived: false },
    ],
    'GET /users?archived=true': [],
    'GET /projects/p1/milestones?archived=true': [milestone('ms0', 'Kickoff', 'COMPLETE')],
    'GET /audit?projectId=p1': [{
      id: 'a1', action: 'project_created', actor: 'admin@acme.com', timestamp: '2026-10-14T12:00:00Z',
      details: { projectId: 'p1', projectName: 'Apollo' },
    }],
    'GET /audit?milestoneId=ms1': [{
      id: 'a2', action: 'milestone_added', actor: 'admin@acme.com', timestamp: '2026-10-14T12:05:00Z',
      details: { projectId: 'p1', projectName: 'Apollo', milestoneId: 'ms1' },
    }],
  });
});
afterEach(cleanup);

describe('project view', () => {
  it('shows the description, money, dependencies and availability', async () => {
    render(<AuthProvider><ProjectDetailPage projectId="p1" /></AuthProvider>);
    expect(await screen.findByText('Crewed lunar landing')).toBeTruthy();
    expect(screen.getByText('$125.50')).toBeTruthy();
    expect(screen.getByText('Blocked by Mercury')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Mercury' })).toBeTruthy();
  });

  it('lists milestones with assignees and what they wait for', async () => {
    render(<AuthProvider><ProjectDetailPage projectId="p1" /></AuthProvider>);
    const design = (await screen.findByText('Design', { selector: 'strong' })).closest('tr');
    expect(within(design).getByText('Eli Employee')).toBeTruthy();
    const build = screen.getByText('Build', { selector: 'strong' }).closest('tr');
    expect(within(build).getByText('Waiting on Design')).toBeTruthy();
  });

  it('shows archived milestones on request', async () => {
    render(<AuthProvider><ProjectDetailPage projectId="p1" /></AuthProvider>);
    await screen.findByText('Design', { selector: 'strong' });
    expect(screen.queryByText('Kickoff')).toBeNull();
    await userEvent.click(screen.getByLabelText('Show archived milestones'));
    expect(await screen.findByText('Kickoff')).toBeTruthy();
  });

  it('shows the project history, narrowed to a milestone on request', async () => {
    render(<AuthProvider><ProjectDetailPage projectId="p1" /></AuthProvider>);
    expect(await screen.findByText('Project created')).toBeTruthy();
    const design = screen.getByText('Design', { selector: 'strong' }).closest('tr');
    await userEvent.click(within(design).getByRole('button', { name: 'History' }));
    expect(await screen.findByRole('heading', { name: 'History of milestone Design' })).toBeTruthy();
    expect(await screen.findByText('Milestone added')).toBeTruthy();
    expect(screen.queryByText('Project created')).toBeNull();
  });
});
