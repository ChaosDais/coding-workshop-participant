import { useAuth } from '../auth/AuthContext.jsx';
import { roleLabel } from '../utils/format.js';

/**
 * Shown to employees until their own view is built. Their account settings and sign-out are under Options.
 * @returns {JSX.Element} The placeholder page.
 */
function OtherRolePage() {
  const { user } = useAuth();
  return (
    <main className="page narrow">
      <h1>{`Welcome, ${user.name}`}</h1>
      <p>{`You are signed in as an ${roleLabel(user.role).toLowerCase()}. The employee view is coming soon.`}</p>
      <p><a href="#/options/account">Your account options</a></p>
    </main>
  );
}

export default OtherRolePage;
