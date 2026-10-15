// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it,
} from 'vitest';
import {
  cleanup, render, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App.jsx';
import { getSession, setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const admin = {
  id: 'a1', name: 'Ada Admin', email: 'ada@acme.com', role: 'ADMIN', jobType: 'Operations', salary: 90000,
  archived: false, passwordChangeRequired: false,
};
const manager = { ...admin, id: 'm1', name: 'Morgan Manager', email: 'morgan@acme.com', role: 'MANAGER' };

let saved;

function signInAs(user) {
  setSession({ accessToken: 'a', refreshToken: 'r', user });
}

beforeEach(() => {
  saved = undefined;
  localStorage.clear();
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.fontSize;
  fakeBackend({
    'GET /settings/bottlenecks': { awaitingReviewDays: 3, anyStatusDays: 30 },
    'PUT /settings/bottlenecks': (body) => {
      saved = body;
      return { status: 200, body };
    },
    'PUT /auth/me': (body) => ({ status: 200, body: { ...admin, ...body } }),
  });
});
afterEach(() => {
  cleanup();
  window.location.hash = '';
  setSession(undefined);
});

describe('options', () => {
  it('replaces the sign-out and password buttons in the navigation bar with Options', async () => {
    signInAs(admin);
    window.location.hash = '#/options/account';
    render(<App />);
    const header = screen.getByRole('banner');
    expect(within(header).getByRole('link', { name: 'Options' })).toBeTruthy();
    expect(within(header).queryByRole('button', { name: /sign out/i })).toBeNull();
    expect(within(header).queryByRole('link', { name: /change password/i })).toBeNull();
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Account', 'Appearance', 'System']);
  });

  it('shows the account and saves a new name and email', async () => {
    signInAs(admin);
    window.location.hash = '#/options/account';
    render(<App />);
    expect(screen.getByText('Operations')).toBeTruthy();
    // Pay is not shown in Options.
    expect(screen.queryByText('$90,000.00')).toBeNull();
    expect(screen.queryByText('Salary')).toBeNull();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Change Password' })).toBeTruthy();

    await userEvent.clear(screen.getByLabelText(/^Name/));
    await userEvent.type(screen.getByLabelText(/^Name/), 'Ada Lovelace');
    await userEvent.click(screen.getByRole('button', { name: 'Save Details' }));
    expect((await screen.findByRole('status')).textContent).toContain('saved');
    expect(getSession().user.name).toBe('Ada Lovelace');
  });

  it('remembers the chosen theme and applies it to the page', async () => {
    signInAs(manager);
    window.location.hash = '#/options/appearance';
    render(<App />);
    await userEvent.click(screen.getByLabelText('Dark'));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('acme.theme')).toBe('dark');
    await userEvent.click(screen.getByLabelText('Match my device'));
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it('remembers the chosen text size and applies it to the whole page', async () => {
    signInAs(manager);
    window.location.hash = '#/options/appearance';
    render(<App />);
    const sizes = screen.getByRole('group', { name: 'Text size' });
    expect(within(sizes).getByLabelText('Default').checked).toBe(true);
    await userEvent.click(within(sizes).getByLabelText('Larger'));
    expect(document.documentElement.dataset.fontSize).toBe('larger');
    expect(localStorage.getItem('acme.fontSize')).toBe('larger');
    expect(within(sizes).getByLabelText('Larger').closest('label').className).toContain('selected');
    await userEvent.click(within(sizes).getByLabelText('Default'));
    expect(document.documentElement.dataset.fontSize).toBeUndefined();
  });

  it('lets admins, and only admins, change the bottleneck thresholds', async () => {
    signInAs(admin);
    window.location.hash = '#/options/system';
    render(<App />);
    const review = await screen.findByLabelText(/^AWAITING REVIEW for/);
    expect(review.value).toBe('3');

    await userEvent.clear(review);
    await userEvent.type(review, '0');
    await userEvent.click(screen.getByRole('button', { name: 'Save Thresholds' }));
    expect(screen.getByText('AWAITING REVIEW must be a whole number of days from 1 to 365')).toBeTruthy();
    expect(saved).toBeUndefined();

    await userEvent.clear(review);
    await userEvent.type(review, '5');
    await userEvent.click(screen.getByRole('button', { name: 'Save Thresholds' }));
    expect(await screen.findByText('Thresholds saved.')).toBeTruthy();
    expect(saved).toEqual({ awaitingReviewDays: 5, anyStatusDays: 30 });
  });

  it('gives managers no System tab, even by address', () => {
    signInAs(manager);
    window.location.hash = '#/options/system';
    render(<App />);
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Account', 'Appearance']);
    expect(screen.getByRole('tab', { name: 'Account' }).getAttribute('aria-selected')).toBe('true');
  });
});
