import PropTypes from 'prop-types';
import { roleLabel } from '../utils/format.js';

const LINKS = {
  ADMIN: [
    { section: 'projects', label: 'Projects', href: '#/projects' },
    { section: 'employees', label: 'Employees', href: '#/employees' },
    { section: 'audit', label: 'Audit log', href: '#/audit' },
  ],
  MANAGER: [
    { section: 'projects', label: 'Projects', href: '#/projects' },
    { section: 'employees', label: 'Employees', href: '#/employees' },
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
      <a className="brand" href="#/projects">ACME Projects</a>
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
        <a href="#/options/account" className="options-link" aria-current={section === 'options' ? 'page' : undefined}>
          Options
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
