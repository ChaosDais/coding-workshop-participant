import PropTypes from 'prop-types';
import { roleLabel } from '../utils/format.js';

const LINKS = [
  { section: 'projects', label: 'Projects', href: '#/projects' },
  { section: 'employees', label: 'Employees', href: '#/employees' },
  { section: 'audit', label: 'Audit log', href: '#/audit' },
];

/**
 * Top navigation for admins, with the signed-in user and sign-out.
 * @param {object} props Component props.
 * @param {string} props.section The current section, to highlight its link.
 * @param {{name: string, role: string}} props.user Signed-in user.
 * @param {Function} props.onSignOut Signs out.
 * @returns {JSX.Element} The navigation bar.
 */
function NavBar({ section, user, onSignOut }) {
  return (
    <header className="navbar">
      <a className="brand" href="#/projects">ACME Projects</a>
      <nav aria-label="Main">
        {LINKS.map((link) => (
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
        <a href="#/account/password">Change password</a>
        <button type="button" className="link-button" onClick={onSignOut}>Sign out</button>
      </div>
    </header>
  );
}

NavBar.propTypes = {
  section: PropTypes.string.isRequired,
  user: PropTypes.shape({ name: PropTypes.string, role: PropTypes.string }).isRequired,
  onSignOut: PropTypes.func.isRequired,
};

export default NavBar;
