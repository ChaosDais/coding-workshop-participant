import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from './Field.jsx';
import ErrorMessage from './ErrorMessage.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { compact, password, required } from '../utils/validation.js';

/**
 * Changes the signed-in user's own password: the current one, then the new one twice.
 * @param {object} props Component props.
 * @param {JSX.Element} [props.secondaryAction] Shown next to the submit button, e.g. Sign out.
 * @returns {JSX.Element} The form.
 */
function ChangePasswordForm({ secondaryAction }) {
  const { changePassword } = useAuth();
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
      next: password(values.next)
        ?? (values.next === values.current ? 'Choose a password different from the current one' : undefined),
      confirm: values.confirm === values.next ? undefined : 'The passwords do not match',
    });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSaving(true);
    setSubmitError(undefined);
    setDone(false);
    try {
      await changePassword(values.current, values.next);
      setValues({ current: '', next: '', confirm: '' });
      setDone(true);
    } catch (error) {
      setSubmitError(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
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
        {secondaryAction}
        <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Change Password'}</button>
      </div>
    </form>
  );
}

ChangePasswordForm.propTypes = {
  secondaryAction: PropTypes.node,
};

export default ChangePasswordForm;
