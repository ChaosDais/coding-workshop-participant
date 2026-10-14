import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from './Field.jsx';
import ErrorMessage from './ErrorMessage.jsx';

/**
 * Logs hours worked on a milestone by hand.
 * @param {object} props Component props.
 * @param {Function} props.onSubmit Receives hours and note; may throw an error to show.
 * @param {Function} props.onCancel Closes the form.
 * @returns {JSX.Element} The form.
 */
function LogTimeForm({ onSubmit, onCancel }) {
  const [values, setValues] = useState({ hours: '', note: '' });
  const [error, setError] = useState();
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const hours = Number(values.hours);
    if (values.hours === '' || !Number.isFinite(hours) || hours <= 0 || hours > 24) {
      setError('Enter the hours worked: more than 0 and at most 24');
      return;
    }
    setError(undefined);
    setSaving(true);
    setSubmitError(undefined);
    try {
      await onSubmit(hours, values.note.trim());
    } catch (failure) {
      setSubmitError(failure);
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <fieldset disabled={saving}>
        <Field id="log-hours" label="Hours worked" required error={error} hint="For example 1.5 for an hour and a half.">
          {(props) => <input id={props.id} type="number" min="0.01" max="24" step="0.25" value={values.hours} onChange={set('hours')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
        </Field>
        <Field id="log-note" label="Note">
          {(props) => <input id={props.id} value={values.note} onChange={set('note')} placeholder="What you worked on" />}
        </Field>
      </fieldset>
      <ErrorMessage error={submitError} />
      <div className="form-actions">
        <button type="button" onClick={onCancel} disabled={saving}>Cancel</button>
        <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Log Time'}</button>
      </div>
    </form>
  );
}

LogTimeForm.propTypes = {
  onSubmit: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};

export default LogTimeForm;
