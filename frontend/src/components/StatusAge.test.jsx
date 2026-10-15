// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import StatusAge from './StatusAge.jsx';

const now = Date.parse('2026-10-14T12:00:00Z');
const item = (status, changed) => ({
  status, statusChangedAt: changed, startDate: '2026-01-01', endDate: '2026-12-31',
});

afterEach(cleanup);

describe('StatusAge', () => {
  it('shows the days in the status with full-caps status names', () => {
    render(<StatusAge item={item('IN_PROGRESS', '2026-10-12T12:00:00Z')} now={now} />);
    expect(screen.getByText('2 days in IN PROGRESS')).toBeTruthy();
  });

  it('shows only the warning when asked, and nothing when there is none', () => {
    const { container } = render(<StatusAge item={item('IN_PROGRESS', '2026-10-12T12:00:00Z')} now={now} warningOnly />);
    expect(container.textContent).toBe('');
    cleanup();

    render(<StatusAge item={item('AWAITING_REVIEW', '2026-10-01T12:00:00Z')} now={now} warningOnly />);
    expect(screen.getByText('⚠ Waiting for review for 13 days')).toBeTruthy();
    expect(screen.queryByText(/days in/)).toBeNull();
  });
});
