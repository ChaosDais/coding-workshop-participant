import { useState } from 'react';
import PropTypes from 'prop-types';
import Field from './Field.jsx';
import ErrorMessage from './ErrorMessage.jsx';
import { STATUSES, statusLabel } from '../utils/format.js';
import {
  compact, dateRange, endBeforeDependents, expectedHoursFit, nonNegativeNumber, required, startAfterDependencies,
  startNotPast,
} from '../utils/validation.js';
import { dayOf } from '../utils/workload.js';

const EMPTY = {
  name: '', description: '', startDate: '', endDate: '', manager: '', budget: '', expectedHours: '', status: 'TODO',
  dependsOn: [],
};

/**
 * Creates or edits a project's details and its project dependencies (milestones are edited separately).
 * @param {object} props Component props.
 * @param {object} [props.project] The project to edit; omitted to create one.
 * @param {object[]} props.managers Active managers to choose from.
 * @param {object[]} props.projects Other active projects it may depend on.
 * @param {Function} props.onSubmit Receives the request body; may throw an error to show.
 * @param {Function} props.onCancel Closes the form.
 * @returns {JSX.Element} The form.
 */
function ProjectForm({
  project, managers, projects, onSubmit, onCancel,
}) {
  const [values, setValues] = useState(() => (project ? {
    ...EMPTY,
    ...project,
    description: project.description ?? '',
    budget: String(project.budget),
    expectedHours: project.expectedHours === undefined || project.expectedHours === null ? ''
      : String(project.expectedHours),
  } : EMPTY));
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));
  const toggleDependency = (id) => setValues((current) => ({
    ...current,
    dependsOn: current.dependsOn.includes(id)
      ? current.dependsOn.filter((other) => other !== id) : [...current.dependsOn, id],
  }));

  const submit = async (event) => {
    event.preventDefault();
    const range = dateRange(values.startDate, values.endDate);
    const dependencies = projects.filter((other) => values.dependsOn.includes(other.id));
    const dependents = project ? projects.filter((other) => (other.dependsOn ?? []).includes(project.id)) : [];
    range.start = range.start
      ?? startNotPast(values.startDate, project?.startDate, dayOf(new Date()))
      ?? startAfterDependencies(values.startDate, dependencies);
    if (!range.end && project && values.endDate !== project.endDate) {
      range.end = endBeforeDependents(values.endDate, dependents);
    }
    // Everyone assigned to the project's milestones counts toward what is possible; at least one person.
    const people = new Set((project?.milestones ?? []).flatMap((milestone) => milestone.assignedEmployees)).size;
    const found = compact({
      name: required(values.name, 'Name'),
      manager: required(values.manager, 'Manager'),
      budget: nonNegativeNumber(values.budget, 'Budget'),
      expectedHours: nonNegativeNumber(values.expectedHours, 'Expected hours', { optional: true })
        ?? expectedHoursFit(values.expectedHours, values.startDate, values.endDate, people, 'everyone assigned to its milestones'),
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
        manager: values.manager,
        budget: Number(values.budget),
        expectedHours: values.expectedHours === '' ? undefined : Number(values.expectedHours),
        status: values.status,
        dependsOn: values.dependsOn,
      });
    } catch (error) {
      setSubmitError(error);
      setSaving(false);
    }
  };

  const others = projects.filter((other) => other.id !== project?.id);

  return (
    <form onSubmit={submit} noValidate>
      <fieldset disabled={saving}>
        <Field id="project-name" label="Name" required error={errors.name}>
          {(props) => <input id={props.id} value={values.name} onChange={set('name')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
        </Field>
        <Field id="project-description" label="Description">
          {(props) => <textarea id={props.id} rows={3} value={values.description} onChange={set('description')} />}
        </Field>
        <div className="field-row">
          <Field id="project-start" label="Start date" required error={errors.start}>
            {(props) => <input id={props.id} type="date" value={values.startDate} onChange={set('startDate')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="project-end" label="Expected end date" required error={errors.end}>
            {(props) => <input id={props.id} type="date" value={values.endDate} onChange={set('endDate')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
        </div>
        <div className="field-row">
          <Field id="project-manager" label="Manager" required error={errors.manager}>
            {(props) => (
              <select id={props.id} value={values.manager} onChange={set('manager')} aria-invalid={props.invalid} aria-describedby={props.describedBy}>
                <option value="">Choose a manager</option>
                {managers.map((manager) => <option key={manager.id} value={manager.id}>{manager.name}</option>)}
              </select>
            )}
          </Field>
          <Field id="project-budget" label="Budget (USD)" required error={errors.budget}>
            {(props) => <input id={props.id} type="number" min="0" step="0.01" value={values.budget} onChange={set('budget')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="project-expected" label="Expected hours" error={errors.expectedHours}>
            {(props) => <input id={props.id} type="number" min="0" step="0.5" value={values.expectedHours} onChange={set('expectedHours')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
          </Field>
          <Field id="project-status" label="Status">
            {(props) => (
              <select id={props.id} value={values.status} onChange={set('status')}>
                {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
              </select>
            )}
          </Field>
        </div>
        <fieldset className="checkbox-group">
          <legend>Depends on</legend>
          {others.length === 0 && <p className="muted">No other active projects.</p>}
          {others.map((other) => (
            <label key={other.id} className="checkbox">
              <input type="checkbox" checked={values.dependsOn.includes(other.id)} onChange={() => toggleDependency(other.id)} />
              {other.name}
            </label>
          ))}
        </fieldset>
      </fieldset>
      <ErrorMessage error={submitError} />
      <div className="form-actions">
        <button type="button" onClick={onCancel} disabled={saving}>Cancel</button>
        <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Save project'}</button>
      </div>
    </form>
  );
}

ProjectForm.propTypes = {
  project: PropTypes.object,
  managers: PropTypes.arrayOf(PropTypes.object).isRequired,
  projects: PropTypes.arrayOf(PropTypes.object).isRequired,
  onSubmit: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};

export default ProjectForm;
