import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from './Field.jsx';
import ErrorMessage from './ErrorMessage.jsx';
import { ROLES, roleLabel } from '../utils/format.js';
import {
  compact, email, nonNegativeNumber, password, required,
} from '../utils/validation.js';

const EMPTY = {
  name: '', email: '', role: 'EMPLOYEE', managerId: '', jobType: '', salary: '', password: '',
};

/**
 * Adds a person or edits one. A new person gets a starting password from the admin.
 * @param {object} props Component props.
 * @param {object} [props.user] The person to edit; omitted to add one.
 * @param {object[]} props.managers Active managers an employee can report to.
 * @param {Function} props.onSubmit Receives the request body; may throw an error to show.
 * @param {Function} props.onCancel Closes the form.
 * @returns {JSX.Element} The form.
 */
function UserForm({
  user, managers, onSubmit, onCancel,
}) {
  const [values, setValues] = useState(() => (user ? {
    ...EMPTY, ...user, managerId: user.managerId ?? '', jobType: user.jobType ?? '',
    salary: user.salary === undefined || user.salary === null ? '' : String(user.salary),
  } : EMPTY));
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    const found = compact({
      name: required(values.name, 'Name'),
      email: email(values.email),
      salary: nonNegativeNumber(values.salary, 'Salary', { optional: true }),
      password: user ? undefined : password(values.password),
    });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSaving(true);
    setSubmitError(undefined);
    try {
      await onSubmit({
        name: values.name.trim(),
        email: values.email.trim(),
        role: values.role,
        managerId: values.role === 'EMPLOYEE' && values.managerId ? values.managerId : undefined,
        jobType: values.jobType.trim() || undefined,
        salary: values.salary === '' ? undefined : Number(values.salary),
        ...(user ? {} : { password: values.password }),
      });
    } catch (error) {
      setSubmitError(error);
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <fieldset disabled={saving}>
        <div className="field-row">
          <Field id="user-name" label="Name" required error={errors.name}>
            {(props) => <input id={props.id} value={values.name} onChange={set('name')} autoComplete="off" aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="user-email" label="Email" required error={errors.email}>
            {(props) => <input id={props.id} type="email" value={values.email} onChange={set('email')} autoComplete="off" aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        </div>
        <div className="field-row">
          <Field id="user-role" label="Role">
            {(props) => (
              <select id={props.id} value={values.role} onChange={set('role')}>
                {ROLES.map((role) => <option key={role} value={role}>{roleLabel(role)}</option>)}
              </select>
            )}
          </Field>
          {values.role === 'EMPLOYEE' && (
            <Field id="user-manager" label="Manager">
              {(props) => (
                <select id={props.id} value={values.managerId} onChange={set('managerId')}>
                  <option value="">No manager yet</option>
                  {managers.map((manager) => <option key={manager.id} value={manager.id}>{manager.name}</option>)}
                </select>
              )}
            </Field>
          )}
        </div>
        <div className="field-row">
          <Field id="user-job" label="Job type">
            {(props) => <input id={props.id} value={values.jobType} onChange={set('jobType')} />}
          </Field>
          <Field id="user-salary" label="Annual salary (USD)" error={errors.salary}>
            {(props) => <input id={props.id} type="number" min="0" step="1" value={values.salary} onChange={set('salary')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        </div>
        {!user && (
          <Field id="user-password" label="Starting password" required error={errors.password} hint="At least 8 characters. Share it with the person securely.">
            {(props) => <input id={props.id} type="password" value={values.password} onChange={set('password')} autoComplete="new-password" aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        )}
      </fieldset>
      <ErrorMessage error={submitError} />
      <div className="form-actions">
        <button type="button" onClick={onCancel} disabled={saving}>Cancel</button>
        <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : user ? 'Save changes' : 'Add person'}</button>
      </div>
    </form>
  );
}

UserForm.propTypes = {
  user: PropTypes.object,
  managers: PropTypes.arrayOf(PropTypes.object).isRequired,
  onSubmit: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};

export default UserForm;
