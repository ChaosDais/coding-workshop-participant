import { useState } from 'react';
import Field from '../components/Field.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { navigate } from '../hooks/useHashRoute.js';
import { compact, email as checkEmail, required } from '../utils/validation.js';

/**
 * Email and password sign-in. There is no sign-up: admins create every account.
 * @returns {JSX.Element} The sign-in page.
 */
function SignInPage() {
  const { signIn } = useAuth();
  const [values, setValues] = useState({ email: '', password: '' });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [signingIn, setSigningIn] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const found = compact({ email: checkEmail(values.email), password: required(values.password, 'Password') });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSigningIn(true);
    setSubmitError(undefined);
    try {
      // Every sign-in starts on the home page (the project list), not whatever page was open last.
      navigate('/projects');
      await signIn(values.email.trim(), values.password);
    } catch (error) {
      setSubmitError(error);
      setSigningIn(false);
    }
  };

  return (
    <main className="auth-page">
      <form className="auth-card" onSubmit={submit} noValidate>
        <h1>Project Manager</h1>
        <p className="muted">Sign in with the account your administrator gave you.</p>
        <fieldset disabled={signingIn}>
          <Field id="sign-in-email" label="Email" error={errors.email}>
            {(props) => <input id={props.id} type="email" autoComplete="username" value={values.email} onChange={set('email')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="sign-in-password" label="Password" error={errors.password}>
            {(props) => <input id={props.id} type="password" autoComplete="current-password" value={values.password} onChange={set('password')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        </fieldset>
        <ErrorMessage error={submitError} />
        <button type="submit" className="primary wide" disabled={signingIn}>
          {signingIn ? <><span className="spinner small" aria-hidden="true" />Signing in… the first sign-in can take up to 30 seconds</> : 'Sign in'}
        </button>
      </form>
    </main>
  );
}

export default SignInPage;
