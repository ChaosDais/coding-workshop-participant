import { useAuth } from '../auth/AuthContext.jsx';
import { roleLabel } from '../utils/format.js';

/**
 * Shown to managers and employees until their own views are built.
 * @returns {JSX.Element} The placeholder page.
 */
function OtherRolePage() {
  const { user, signOut } = useAuth();
  return (
    <main className="auth-page">
      <div className="auth-card">
        <h1>{`Welcome, ${user.name}`}</h1>
        <p>{`You are signed in as a ${roleLabel(user.role).toLowerCase()}. The ${roleLabel(user.role).toLowerCase()} view is coming soon.`}</p>
        <div className="form-actions">
          <a href="#/account/password">Change password</a>
          <button type="button" onClick={signOut}>Sign out</button>
        </div>
      </div>
    </main>
  );
}

export default OtherRolePage;
