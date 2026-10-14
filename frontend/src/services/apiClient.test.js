import { describe, expect, it } from 'vitest';
import { fillPlaceholders } from './apiClient.js';

describe('fillPlaceholders', () => {
  it('fills known values in paths and bodies', () => {
    expect(fillPlaceholders('/projects/{projectId}/milestones/{milestoneId}', { projectId: 'p1', milestoneId: 'm1' }))
      .toBe('/projects/p1/milestones/m1');
    expect(fillPlaceholders('{"refreshToken": "{refreshToken}"}', { refreshToken: 'abc' }))
      .toBe('{"refreshToken": "abc"}');
  });

  it('leaves placeholders without a value so the request stays visibly invalid', () => {
    expect(fillPlaceholders('/projects/{projectId}', { projectId: '' })).toBe('/projects/{projectId}');
  });

  it('does not touch ordinary JSON braces', () => {
    expect(fillPlaceholders('{ "name": "x" }', {})).toBe('{ "name": "x" }');
  });
});
