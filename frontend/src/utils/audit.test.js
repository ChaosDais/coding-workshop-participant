import { describe, expect, it } from 'vitest';
import { actionLabel, actorName, describeDetails } from './audit.js';

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
      .toBe('Apollo: Design · TODO → COMPLETE');
  });

  it('prefers the stored milestone name, so renamed or archived milestones keep their name', () => {
    const entry = { details: { projectName: 'Apollo', milestoneId: 'm1', milestoneName: 'Design' } };
    expect(describeDetails(entry, { milestones: new Map([['m1', { name: 'Renamed' }]]) }))
      .toBe('Apollo: Design');
    expect(describeDetails({ details: { milestoneId: 'abcdef123456' } })).toBe('Unknown milestone');
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
    expect(describeDetails(entry)).toBe('5 h → 30 min · “Typo”');
  });
});

describe('names instead of ids', () => {
  const users = new Map([['u1', { name: 'Ada', email: 'ada@example.com' }]]);

  it('names who made the change: stored name first, then by email', () => {
    expect(actorName({ actor: 'x@example.com', details: { actorName: 'Xavier' } })).toBe('Xavier');
    expect(actorName({ actor: 'ada@example.com', details: {} }, { users })).toBe('Ada');
    expect(actorName({ actor: 'system', details: {} }, { users })).toBe('system');
  });

  it('never shows an id, even when nothing is known about it', () => {
    const text = describeDetails({
      details: {
        employeeId: 'e-123456789', userId: 'u-123456789', toManagerId: 'm-123456789', fromManagerId: null,
      },
    });
    expect(text).not.toMatch(/123456/);
    expect(text).toContain('an unknown employee');
  });

  it('uses the names stored with time entries and team moves', () => {
    expect(describeDetails({
      details: {
        projectName: 'Apollo', milestoneId: 'm1', milestoneName: 'Design', employeeId: 'e1', employeeName: 'Ada', hours: '1.25',
      },
    })).toBe('Apollo: Design · Ada · 1 h 15 min');
    expect(describeDetails({
      details: {
        userId: 'e1', name: 'Ada', fromManagerId: 'm1', fromManagerName: 'Morgan', toManagerId: 'm2', toManagerName: 'Sam',
      },
    })).toBe('Ada · Morgan → Sam');
  });
});
