import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from './Field.jsx';
import ErrorMessage from './ErrorMessage.jsx';
import { STATUSES, statusLabel } from '../utils/format.js';
import {
  compact, dateRange, nonNegativeNumber, required,
} from '../utils/validation.js';

const EMPTY = {
  name: '', description: '', startDate: '', endDate: '', progressTime: '0', expectedHours: '', status: 'TODO',
  assignedEmployees: [], dependsOn: [],
};

/**
 * Creates or edits a milestone: its details, assigned employees and the milestones it depends on.
 * @param {object} props Component props.
 * @param {object} [props.milestone] The milestone to edit; omitted to add one.
 * @param {{startDate: string, endDate: string}} props.project The project, whose dates bound the milestone's.
 * @param {object[]} props.team Employees on the project manager's team, who can be assigned.
 * @param {object[]} props.milestones The project's other milestones, which it may depend on.
 * @param {Map<string, string>} props.workingOn For employees on another unfinished milestone of the project, its
 *   name; an employee works on one unfinished milestone per project, so they cannot be newly assigned here.
 * @param {Map<string, object>} props.usersById Every user, to name assignees who left the team.
 * @param {Function} props.onSubmit Receives the request body; may throw an error to show.
 * @param {Function} props.onCancel Closes the form.
 * @returns {JSX.Element} The form.
 */
function MilestoneForm({
  milestone, project, team, milestones, workingOn, usersById, onSubmit, onCancel,
}) {
  const [values, setValues] = useState(() => (milestone ? {
    ...EMPTY,
    ...milestone,
    description: milestone.description ?? '',
    progressTime: String(milestone.progressTime),
    expectedHours: milestone.expectedHours === undefined || milestone.expectedHours === null ? ''
      : String(milestone.expectedHours),
  } : { ...EMPTY, startDate: project.startDate, endDate: project.endDate }));
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));
  const toggle = (field, id) => setValues((current) => ({
    ...current,
    [field]: current[field].includes(id) ? current[field].filter((other) => other !== id) : [...current[field], id],
  }));

  const submit = async (event) => {
    event.preventDefault();
    const range = dateRange(values.startDate, values.endDate);
    if (!range.start && values.startDate < project.startDate) {
      range.start = `Must be on or after the project's start (${project.startDate})`;
    }
    if (!range.end && values.endDate > project.endDate) {
      range.end = `Must be on or before the project's end (${project.endDate})`;
    }
    const found = compact({
      name: required(values.name, 'Name'),
      progressTime: nonNegativeNumber(values.progressTime, 'Progress'),
      expectedHours: nonNegativeNumber(values.expectedHours, 'Expected hours', { optional: true }),
      ...range,
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
        description: values.description.trim(),
        startDate: values.startDate,
        endDate: values.endDate,
        progressTime: Number(values.progressTime),
        expectedHours: values.expectedHours === '' ? undefined : Number(values.expectedHours),
        status: values.status,
        assignedEmployees: values.assignedEmployees,
        dependsOn: values.dependsOn,
      });
    } catch (error) {
      setSubmitError(error);
      setSaving(false);
    }
  };

  // Employees already assigned stay selectable even if they have since moved to another team.
  const assignable = [...team];
  values.assignedEmployees.forEach((id) => {
    if (!assignable.some((employee) => employee.id === id)) {
      assignable.push(usersById.get(id) ?? { id, name: 'Former team member' });
    }
  });
  const others = milestones.filter((other) => other.id !== milestone?.id);

  return (
    <form onSubmit={submit} noValidate>
      <fieldset disabled={saving}>
        <Field id="milestone-name" label="Name" required error={errors.name}>
          {(props) => <input id={props.id} value={values.name} onChange={set('name')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
        </Field>
        <Field id="milestone-description" label="Description">
          {(props) => <textarea id={props.id} rows={3} value={values.description} onChange={set('description')} />}
        </Field>
        <div className="field-row">
          <Field id="milestone-start" label="Start date" required error={errors.start}>
            {(props) => <input id={props.id} type="date" value={values.startDate} min={project.startDate} max={project.endDate} onChange={set('startDate')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="milestone-end" label="End date" required error={errors.end}>
            {(props) => <input id={props.id} type="date" value={values.endDate} min={project.startDate} max={project.endDate} onChange={set('endDate')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        </div>
        <div className="field-row">
          <Field id="milestone-progress" label="Progress (hours)" error={errors.progressTime}>
            {(props) => <input id={props.id} type="number" min="0" step="0.25" value={values.progressTime} onChange={set('progressTime')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="milestone-expected" label="Expected hours" error={errors.expectedHours}>
            {(props) => <input id={props.id} type="number" min="0" step="0.5" value={values.expectedHours} onChange={set('expectedHours')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="milestone-status" label="Status">
            {(props) => (
              <select id={props.id} value={values.status} onChange={set('status')}>
                {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
              </select>
            )}
          </Field>
        </div>
        <fieldset className="checkbox-group">
          <legend>Assigned employees</legend>
          {assignable.length === 0 && <p className="muted">The project manager has no employees on their team yet.</p>}
          {assignable.map((employee) => {
            const assigned = values.assignedEmployees.includes(employee.id);
            const alreadyHere = milestone?.assignedEmployees.includes(employee.id);
            const elsewhere = !alreadyHere && values.status !== 'COMPLETE' ? workingOn.get(employee.id) : undefined;
            return (
              <label key={employee.id} className="checkbox">
                <input type="checkbox" checked={assigned} disabled={Boolean(elsewhere) && !assigned} onChange={() => toggle('assignedEmployees', employee.id)} />
                {employee.name}
                {elsewhere && <span className="muted small">{` — already on ${elsewhere}`}</span>}
              </label>
            );
          })}
        </fieldset>
        <fieldset className="checkbox-group">
          <legend>Depends on</legend>
          {others.length === 0 && <p className="muted">No other milestones in this project.</p>}
          {others.map((other) => (
            <label key={other.id} className="checkbox">
              <input type="checkbox" checked={values.dependsOn.includes(other.id)} onChange={() => toggle('dependsOn', other.id)} />
              {other.name}
            </label>
          ))}
        </fieldset>
      </fieldset>
      <ErrorMessage error={submitError} />
      <div className="form-actions">
        <button type="button" onClick={onCancel} disabled={saving}>Cancel</button>
        <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Save milestone'}</button>
      </div>
    </form>
  );
}

MilestoneForm.propTypes = {
  milestone: PropTypes.object,
  project: PropTypes.shape({ startDate: PropTypes.string, endDate: PropTypes.string }).isRequired,
  team: PropTypes.arrayOf(PropTypes.object).isRequired,
  milestones: PropTypes.arrayOf(PropTypes.object).isRequired,
  workingOn: PropTypes.instanceOf(Map).isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
  onSubmit: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};

export default MilestoneForm;
