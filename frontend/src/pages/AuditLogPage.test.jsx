// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it,
} from 'vitest';
import {
  cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuditLogPage from './AuditLogPage.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const TOTAL = 25;
const entry = (number) => ({
  id: `a${number}`, action: 'project_updated', actor: 'ada@example.com', timestamp: '2026-10-14T12:00:00Z',
  details: { projectName: `Project ${number}`, actorName: 'Ada Admin' },
});

/** Pages of the 25 entries, as the backend answers them for each size and offset. */
function pages(size) {
  const routes = {};
  for (let offset = 0; offset < TOTAL; offset += size) {
    const entries = Array.from({ length: Math.min(size, TOTAL - offset) }, (_, index) => entry(offset + index + 1));
    routes[`GET /audit/page?limit=${size}&offset=${offset}`] = {
      entries, total: TOTAL, offset, limit: size,
    };
  }
  return routes;
}

let backend;
beforeEach(() => {
  setSession({ accessToken: 'a', refreshToken: 'r', user: { id: 'admin', name: 'Ada Admin', role: 'ADMIN' } });
  backend = fakeBackend({
    ...pages(100),
    ...pages(10),
    'GET /users': [], 'GET /users?archived=true': [],
  });
});
afterEach(cleanup);

const status = () => screen.getByRole('status').textContent;
const lastRequest = () => new URL(backend.mock.calls.at(-1)[0]).search;

describe('audit log page', () => {
  it('shows everything on one page, without page controls, when it fits', async () => {
    render(<AuditLogPage />);
    expect(await screen.findByText(/Project 25/)).toBeTruthy();
    expect(status()).toBe('Showing 1–25 of 25 entries');
    expect(screen.queryByRole('navigation', { name: /Audit log pages/ })).toBeNull();
  });

  it('moves between pages of 10 from controls above and below the list', async () => {
    const user = userEvent.setup();
    render(<AuditLogPage />);
    await screen.findByText(/Project 25/);
    await user.selectOptions(screen.getByLabelText('Per page'), '10');
    expect(await screen.findByText('Showing 1–10 of 25 entries')).toBeTruthy();
    const top = screen.getByRole('navigation', { name: 'Audit log pages, top' });
    const bottom = screen.getByRole('navigation', { name: 'Audit log pages, bottom' });
    expect(within(top).getByText(/of 3/)).toBeTruthy();
    expect(within(top).getByRole('button', { name: 'Previous page' }).disabled).toBe(true);

    await user.click(within(top).getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Showing 11–20 of 25 entries')).toBeTruthy();
    expect(lastRequest()).toBe('?limit=10&offset=10');

    await user.click(within(screen.getByRole('navigation', { name: 'Audit log pages, bottom' })).getByRole('button', { name: 'Last page' }));
    expect(await screen.findByText('Showing 21–25 of 25 entries')).toBeTruthy();
    expect(within(screen.getByRole('navigation', { name: 'Audit log pages, top' })).getByRole('button', { name: 'Next page' }).disabled).toBe(true);

    await user.click(within(screen.getByRole('navigation', { name: 'Audit log pages, top' })).getByRole('button', { name: 'First page' }));
    expect(await screen.findByText('Showing 1–10 of 25 entries')).toBeTruthy();
    expect(bottom).toBeTruthy();
  });

  it('goes to a typed page number, kept within the pages there are', async () => {
    const user = userEvent.setup();
    render(<AuditLogPage />);
    await screen.findByText(/Project 25/);
    await user.selectOptions(screen.getByLabelText('Per page'), '10');
    await screen.findByText('Showing 1–10 of 25 entries');

    const top = screen.getByRole('navigation', { name: 'Audit log pages, top' });
    const number = within(top).getByLabelText('Page number');
    await user.clear(number);
    await user.type(number, '2{Enter}');
    expect(await screen.findByText('Showing 11–20 of 25 entries')).toBeTruthy();

    const again = within(screen.getByRole('navigation', { name: 'Audit log pages, top' })).getByLabelText('Page number');
    await user.clear(again);
    await user.type(again, '99{Enter}');
    expect(await screen.findByText('Showing 21–25 of 25 entries')).toBeTruthy();
    expect(again.value).toBe('3');
  });
});
