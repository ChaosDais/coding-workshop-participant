import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from './Field.jsx';
import ErrorMessage from './ErrorMessage.jsx';
import { compact, password } from '../utils/validation.js';

/**
 * Lets an admin set a new password for someone else.
 * @param {object} props Component props.
 * @param {{name: string}} props.user The person whose password changes.
 * @param {Function} props.onSubmit Receives the new password; may throw an error to show.
 * @param {Function} props.onCancel Closes the form.
 * @returns {JSX.Element} The form.
 */
function ResetPasswordForm({ user, onSubmit, onCancel }) {
  const [values, setValues] = useState({ next: '', confirm: '' });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const found = compact({
      next: password(values.next),
      confirm: values.confirm === values.next ? undefined : 'The passwords do not match',
    });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSaving(true);
    setSubmitError(undefined);
    try {
      await onSubmit(values.next);
    } catch (error) {
      setSubmitError(error);
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <p>{`${user.name} will be signed out everywhere and will need the new password to sign in. Share it with them securely.`}</p>
      <fieldset disabled={saving}>
        <Field id="reset-password" label="New password" required error={errors.next} hint="At least 8 characters.">
          {(props) => <input id={props.id} type="password" autoComplete="new-password" value={values.next} onChange={set('next')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
        </Field>
        <Field id="reset-password-confirm" label="Confirm new password" required error={errors.confirm}>
          {(props) => <input id={props.id} type="password" autoComplete="new-password" value={values.confirm} onChange={set('confirm')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
        </Field>
      </fieldset>
      <ErrorMessage error={submitError} />
      <div className="form-actions">
        <button type="button" onClick={onCancel} disabled={saving}>Cancel</button>
        <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Set password'}</button>
      </div>
    </form>
  );
}

ResetPasswordForm.propTypes = {
  user: PropTypes.shape({ name: PropTypes.string.isRequired }).isRequired,
  onSubmit: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};

export default ResetPasswordForm;
