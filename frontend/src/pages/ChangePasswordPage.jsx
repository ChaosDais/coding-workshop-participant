import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from '../components/Field.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { compact, password, required } from '../utils/validation.js';

/**
 * Changes the signed-in user's password. The premade admin lands here on first sign-in and cannot leave until done.
 * @param {object} props Component props.
 * @param {boolean} props.forced Whether a password change is required before anything else.
 * @param {Function} [props.onDone] Called after a voluntary change.
 * @returns {JSX.Element} The page.
 */
function ChangePasswordPage({ forced, onDone }) {
  const { changePassword, signOut } = useAuth();
  const [values, setValues] = useState({ current: '', next: '', confirm: '' });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const found = compact({
      current: required(values.current, 'Current password'),
      next: password(values.next) ?? (values.next === values.current ? 'Choose a password different from the current one' : undefined),
      confirm: values.confirm === values.next ? undefined : 'The passwords do not match',
    });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSaving(true);
    setSubmitError(undefined);
    try {
      await changePassword(values.current, values.next);
      setDone(true);
      setSaving(false);
      onDone?.();
    } catch (error) {
      setSubmitError(error);
      setSaving(false);
    }
  };

  return (
    <main className={forced ? 'auth-page' : 'page narrow'}>
      <form className="auth-card" onSubmit={submit} noValidate>
        <h1>{forced ? 'Choose your password' : 'Change password'}</h1>
        {forced && <p>This account was set up with a starting password. Choose your own before continuing.</p>}
        {done && <p className="success" role="status">Your password has been changed.</p>}
        <fieldset disabled={saving}>
          <Field id="password-current" label="Current password" error={errors.current}>
            {(props) => <input id={props.id} type="password" autoComplete="current-password" value={values.current} onChange={set('current')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="password-new" label="New password" error={errors.next} hint="At least 8 characters.">
            {(props) => <input id={props.id} type="password" autoComplete="new-password" value={values.next} onChange={set('next')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="password-confirm" label="Confirm new password" error={errors.confirm}>
            {(props) => <input id={props.id} type="password" autoComplete="new-password" value={values.confirm} onChange={set('confirm')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        </fieldset>
        <ErrorMessage error={submitError} />
        <div className="form-actions">
          {forced
            ? <button type="button" onClick={signOut} disabled={saving}>Sign out</button>
            : <a href="#/projects">Back</a>}
          <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Change password'}</button>
        </div>
      </form>
    </main>
  );
}

ChangePasswordPage.propTypes = {
  forced: PropTypes.bool.isRequired,
  onDone: PropTypes.func,
};

export default ChangePasswordPage;
