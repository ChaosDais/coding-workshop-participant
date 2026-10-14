const sampleProject = {
  name: 'Apollo',
  startDate: '2026-01-01',
  endDate: '2026-12-31',
  progressTime: 12.5,
  manager: 'alice',
  budget: 50000.75,
  status: 'TODO',
};

const sampleMilestone = {
  name: 'Design',
  startDate: '2026-01-01',
  endDate: '2026-02-01',
  progressTime: 4,
  assignedEmployees: ['bob', 'carol'],
  status: 'IN_PROGRESS',
};

const json = (value) => JSON.stringify(value, null, 2);

/**
 * Ready-made requests. "captures" names the id field that is remembered from a
 * successful response so follow-up presets can use it.
 */
export const PRESETS = [
  {
    id: 'root', group: 'General', label: 'Service root', method: 'GET', path: '/',
  },
  {
    id: 'audit', group: 'General', label: 'View audit log', method: 'GET', path: '/audit',
  },
  {
    id: 'list-projects', group: 'Projects', label: 'List projects', method: 'GET', path: '/projects',
  },
  {
    id: 'list-archived-projects',
    group: 'Projects',
    label: 'List archived projects',
    method: 'GET',
    path: '/projects?archived=true',
  },
  {
    id: 'create-project',
    group: 'Projects',
    label: 'Create project',
    method: 'POST',
    path: '/projects',
    body: json(sampleProject),
    captures: 'projectId',
  },
  {
    id: 'get-project', group: 'Projects', label: 'Get project', method: 'GET', path: '/projects/{projectId}',
  },
  {
    id: 'update-project',
    group: 'Projects',
    label: 'Update project',
    method: 'PUT',
    path: '/projects/{projectId}',
    body: json({ ...sampleProject, name: 'Apollo (updated)', status: 'IN_PROGRESS' }),
  },
  {
    id: 'archive-project', group: 'Projects', label: 'Archive project', method: 'DELETE', path: '/projects/{projectId}',
  },
  {
    id: 'restore-project',
    group: 'Projects',
    label: 'Restore project',
    method: 'POST',
    path: '/projects/{projectId}/restore',
  },
  {
    id: 'add-milestone',
    group: 'Milestones',
    label: 'Add milestone',
    method: 'POST',
    path: '/projects/{projectId}/milestones',
    body: json(sampleMilestone),
    captures: 'milestoneId',
  },
  {
    id: 'update-milestone',
    group: 'Milestones',
    label: 'Update milestone',
    method: 'PUT',
    path: '/projects/{projectId}/milestones/{milestoneId}',
    body: json({ ...sampleMilestone, status: 'COMPLETE' }),
  },
  {
    id: 'archive-milestone',
    group: 'Milestones',
    label: 'Archive milestone',
    method: 'DELETE',
    path: '/projects/{projectId}/milestones/{milestoneId}',
  },
  {
    id: 'invalid-dates',
    group: 'Validation',
    label: 'End before start',
    method: 'POST',
    path: '/projects',
    body: json({ ...sampleProject, endDate: '2025-01-01' }),
  },
  {
    id: 'invalid-status',
    group: 'Validation',
    label: 'Unknown status',
    method: 'POST',
    path: '/projects',
    body: json({ ...sampleProject, status: 'DONE' }),
  },
  {
    id: 'malformed-json',
    group: 'Validation',
    label: 'Malformed JSON',
    method: 'POST',
    path: '/projects',
    body: '{ "name": ',
  },
];
