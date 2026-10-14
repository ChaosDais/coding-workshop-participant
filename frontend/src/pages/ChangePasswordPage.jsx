import ChangePasswordForm from '../components/ChangePasswordForm.jsx';
import { useAuth } from '../auth/AuthContext.jsx';

/**
 * Shown instead of the app while an account must choose its own password (the premade admin's first sign-in).
 * Voluntary changes happen in Options → Account.
 * @returns {JSX.Element} The page.
 */
function ChangePasswordPage() {
  const { signOut } = useAuth();
  return (
    <main className="auth-page">
      <div className="auth-card">
        <h1>Choose your password</h1>
        <p>This account was set up with a starting password. Choose your own before continuing.</p>
        <ChangePasswordForm secondaryAction={<button type="button" onClick={signOut}>Sign out</button>} />
      </div>
    </main>
  );
}

export default ChangePasswordPage;
