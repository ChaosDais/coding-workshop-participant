import { describe, expect, it } from 'vitest';
import { filterUsers } from './users.js';

const people = [
  { id: 'aaa111', name: 'Zoe', email: 'zoe@acme.com', role: 'EMPLOYEE', jobType: 'Designer' },
  { id: 'bbb222', name: 'Adam', email: 'adam@acme.com', role: 'MANAGER' },
  { id: 'ccc333', name: 'Maya', email: 'maya@acme.com', role: 'EMPLOYEE', jobType: 'Developer' },
];

describe('filterUsers', () => {
  it('sorts by name and keeps everyone without filters', () => {
    expect(filterUsers(people).map((user) => user.name)).toEqual(['Adam', 'Maya', 'Zoe']);
  });

  it('searches name, email, job and id, ignoring case', () => {
    expect(filterUsers(people, { search: 'DEVELOPER' }).map((user) => user.name)).toEqual(['Maya']);
    expect(filterUsers(people, { search: 'bbb2' }).map((user) => user.name)).toEqual(['Adam']);
    expect(filterUsers(people, { search: 'acme' })).toHaveLength(3);
  });

  it('filters by role, combined with the search', () => {
    expect(filterUsers(people, { role: 'EMPLOYEE' }).map((user) => user.name)).toEqual(['Maya', 'Zoe']);
    expect(filterUsers(people, { role: 'EMPLOYEE', search: 'zoe' }).map((user) => user.name)).toEqual(['Zoe']);
  });
});
