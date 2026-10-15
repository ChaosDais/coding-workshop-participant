import PropTypes from 'prop-types';
import { roleLabel } from '../utils/format.js';

const LINKS = {
  ADMIN: [
    { section: 'projects', label: 'Projects', href: '#/projects' },
    { section: 'employees', label: 'Employees', href: '#/employees' },
    { section: 'workload', label: 'Workload', href: '#/workload' },
    { section: 'audit', label: 'Audit log', href: '#/audit' },
  ],
  MANAGER: [
    { section: 'projects', label: 'Projects', href: '#/projects' },
    { section: 'employees', label: 'Employees', href: '#/employees' },
    { section: 'workload', label: 'Workload', href: '#/workload' },
  ],
  EMPLOYEE: [
    { section: 'projects', label: 'Projects', href: '#/projects' },
  ],
};

/**
 * Top navigation: the sections the signed-in user's role has, their name, and Options (account, appearance and,
 * for admins, system settings; signing out is under Account).
 * @param {object} props Component props.
 * @param {string} props.section The current section, to highlight its link.
 * @param {{name: string, role: string}} props.user Signed-in user.
 * @returns {JSX.Element} The navigation bar.
 */
function NavBar({ section, user }) {
  return (
    <header className="navbar">
      <a className="brand" href="#/projects">Project Manager</a>
      <nav aria-label="Main">
        {(LINKS[user.role] ?? []).map((link) => (
          <a key={link.section} href={link.href} aria-current={link.section === section ? 'page' : undefined}>
            {link.label}
          </a>
        ))}
      </nav>
      <div className="navbar-user">
        <span>
          {user.name}
          <span className="muted">{` · ${roleLabel(user.role)}`}</span>
        </span>
        <a href="#/options/account" className="options-link" aria-label="Options" title="Options" aria-current={section === 'options' ? 'page' : undefined}>
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
            <path fill="currentColor" d="M19.14 12.94a7.07 7.07 0 0 0 .06-.94 7.07 7.07 0 0 0-.06-.94l2.03-1.58a.49.49 0 0 0 .12-.62l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.4h-3.84a.48.48 0 0 0-.48.4l-.36 2.54a7.36 7.36 0 0 0-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.71 8.86a.48.48 0 0 0 .12.62l2.03 1.58a7.4 7.4 0 0 0 0 1.88l-2.03 1.58a.49.49 0 0 0-.12.62l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.04.7 1.62.94l.36 2.54c.05.24.25.4.48.4h3.84c.24 0 .44-.16.48-.4l.36-2.54a7.36 7.36 0 0 0 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.62l-2.01-1.58ZM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2Z" />
          </svg>
        </a>
      </div>
    </header>
  );
}

NavBar.propTypes = {
  section: PropTypes.string.isRequired,
  user: PropTypes.shape({ name: PropTypes.string, role: PropTypes.string }).isRequired,
};

export default NavBar;
