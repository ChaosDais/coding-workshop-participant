// @vitest-environment jsdom
import {
  afterEach, beforeEach, describe, expect, it,
} from 'vitest';
import {
  cleanup, render, screen,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App.jsx';
import { setSession } from '../services/api.js';
import { fakeBackend } from '../test/fakeBackend.js';

const admin = {
  id: 'u1', name: 'Ada Admin', email: 'admin@acme.com', role: 'ADMIN', archived: false, passwordChangeRequired: false,
};

beforeEach(() => {
  setSession(undefined);
  window.location.hash = '';
});
afterEach(cleanup);

describe('signing in', () => {
  it('checks the fields before sending anything', async () => {
    const fetch = fakeBackend({});
    render(<App />);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByText('Email is required')).toBeTruthy();
    expect(screen.getByText('Password is required')).toBeTruthy();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('shows the server message when the credentials are wrong', async () => {
    fakeBackend({ 'POST /auth/login': () => ({ status: 401, body: { error: 'Invalid email or password' } }) });
    render(<App />);
    await userEvent.type(screen.getByLabelText('Email'), 'admin@acme.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Invalid email or password');
  });

  it('sends the premade admin to choose a password before anything else', async () => {
    fakeBackend({
      'POST /auth/login': () => ({
        status: 200,
        body: {
          accessToken: 'a', refreshToken: 'r', user: { ...admin, passwordChangeRequired: true },
        },
      }),
    });
    render(<App />);
    await userEvent.type(screen.getByLabelText('Email'), 'admin@acme.com');
    await userEvent.type(screen.getByLabelText('Password'), 'ChangeMe123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Choose your password' })).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull();
  });

  it('opens the projects page with the navigation bar for an admin, whatever page was open before', async () => {
    fakeBackend({
      'POST /auth/login': () => ({ status: 200, body: { accessToken: 'a', refreshToken: 'r', user: admin } }),
      'GET /projects': [], 'GET /projects?archived=true': [], 'GET /users': [admin], 'GET /users?archived=true': [],
    });
    window.location.hash = '#/audit';
    render(<App />);
    await userEvent.type(screen.getByLabelText('Email'), 'admin@acme.com');
    await userEvent.type(screen.getByLabelText('Password'), 'a-good-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Projects' })).toBeTruthy();
    expect(window.location.hash).toBe('#/projects');
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect([...nav.querySelectorAll('a')].map((link) => link.textContent)).toEqual(['Projects', 'Employees', 'Workload', 'Audit log']);
    expect(await screen.findByText(/No projects yet/)).toBeTruthy();
  });
});
