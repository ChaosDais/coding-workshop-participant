import { describe, expect, it } from 'vitest';
import { actionLabel, describeDetails } from './audit.js';

describe('actionLabel', () => {
  it('turns an action into a sentence', () => {
    expect(actionLabel('milestone_status_changed')).toBe('Milestone status changed');
  });
});

describe('describeDetails', () => {
  it('uses names where it knows them', () => {
    const entry = {
      details: {
        projectId: 'p1', projectName: 'Apollo', milestoneId: 'm1', from: 'TODO', to: 'COMPLETE',
      },
    };
    expect(describeDetails(entry, { milestones: new Map([['m1', { name: 'Design' }]]) }))
      .toBe('Apollo · milestone Design · TODO → COMPLETE');
  });

  it('prefers the stored milestone name, so renamed or archived milestones keep their name', () => {
    const entry = { details: { projectName: 'Apollo', milestoneId: 'm1', milestoneName: 'Design' } };
    expect(describeDetails(entry, { milestones: new Map([['m1', { name: 'Renamed' }]]) }))
      .toBe('Apollo · milestone Design');
    expect(describeDetails({ details: { milestoneId: 'abcdef123456' } })).toBe('milestone abcdef12');
  });

  it('names people, falling back to the lookup for older entries', () => {
    expect(describeDetails({ details: { userId: 'u1', name: 'Eli Employee', email: 'eli@acme.com' } }))
      .toBe('Eli Employee (eli@acme.com)');
    const users = new Map([['u2', { name: 'Morgan Manager' }]]);
    expect(describeDetails({ details: { userId: 'u2' } }, { users })).toBe('Morgan Manager');
  });

  it('shows moves between managers by name', () => {
    const users = new Map([['m1', { name: 'Morgan' }], ['m2', { name: 'Riley' }]]);
    const entry = {
      details: {
        userId: 'e1', name: 'Eli', email: 'eli@acme.com', fromManagerId: 'm1', toManagerId: 'm2',
      },
    };
    expect(describeDetails(entry, { users })).toBe('Eli (eli@acme.com) · Morgan → Riley');
    expect(describeDetails({ details: { userId: 'e1', name: 'Eli', toManagerId: null } }, { users }))
      .toBe('Eli · no manager → no manager');
  });

  it('shows corrections with the hours before and after, and the reason', () => {
    const entry = { details: { hours: '0.50', previousHours: '5.00', reason: 'Typo' } };
    expect(describeDetails(entry)).toBe('5.00 h → 0.50 h · “Typo”');
  });
});
