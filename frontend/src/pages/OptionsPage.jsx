import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import ChangePasswordForm from '../components/ChangePasswordForm.jsx';
import ErrorMessage from '../components/ErrorMessage.jsx';
import Field from '../components/Field.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { navigate } from '../hooks/useHashRoute.js';
import { auth, settingsApi, usersApi } from '../services/api.js';
import { useSettings } from '../settings/SettingsContext.jsx';
import { THEMES, getTheme, setTheme } from '../settings/theme.js';
import { formatMoney, roleLabel } from '../utils/format.js';
import { compact, email as checkEmail, required } from '../utils/validation.js';

/** The tabs each role sees; System is for admins. */
function tabsFor(role) {
  return [
    { id: 'account', label: 'Account' },
    { id: 'appearance', label: 'Appearance' },
    ...(role === 'ADMIN' ? [{ id: 'system', label: 'System' }] : []),
  ];
}

/** Your details, editing your name and email, your password, and signing out. */
function AccountTab() {
  const { user, signOut } = useAuth();
  const [values, setValues] = useState({ name: user.name, email: user.email });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [managerName, setManagerName] = useState();

  useEffect(() => {
    if (user.managerId) {
      usersApi.get(user.managerId).then((manager) => setManagerName(manager.name)).catch(() => {});
    }
  }, [user.managerId]);

  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const save = async (event) => {
    event.preventDefault();
    const found = compact({ name: required(values.name, 'Name'), email: checkEmail(values.email) });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSaving(true);
    setSaved(false);
    setSubmitError(undefined);
    try {
      await auth.updateProfile(values.name.trim(), values.email.trim());
      setSaved(true);
    } catch (error) {
      setSubmitError(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="options-panel">
      <section>
        <h2>Your Account</h2>
        <dl className="summary">
          <div><dt>Role</dt><dd>{roleLabel(user.role)}</dd></div>
          <div><dt>Job</dt><dd>{user.jobType ?? '—'}</dd></div>
          {user.role === 'EMPLOYEE' && <div><dt>Manager</dt><dd>{managerName ?? (user.managerId ? '…' : 'None yet')}</dd></div>}
          <div><dt>Salary</dt><dd>{formatMoney(user.salary)}</dd></div>
          <div><dt>ID</dt><dd><code>{user.id}</code></dd></div>
        </dl>
        <p className="muted small">Only an admin can change your role, job, manager or salary.</p>
      </section>

      <section>
        <h2>Name and Email</h2>
        <form onSubmit={save} noValidate>
          {saved && <p className="success" role="status">Your details have been saved.</p>}
          <fieldset disabled={saving}>
            <div className="field-row">
              <Field id="account-name" label="Name" required error={errors.name}>
                {(props) => <input id={props.id} value={values.name} onChange={set('name')} autoComplete="name" aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
              </Field>
              <Field id="account-email" label="Email" required error={errors.email} hint="You sign in with this address.">
                {(props) => <input id={props.id} type="email" value={values.email} onChange={set('email')} autoComplete="email" aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
              </Field>
            </div>
          </fieldset>
          <ErrorMessage error={submitError} />
          <div className="form-actions">
            <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Save Details'}</button>
          </div>
        </form>
      </section>

      <section>
        <h2>Password</h2>
        <ChangePasswordForm />
      </section>

      <section>
        <h2>Sign Out</h2>
        <p className="muted">Ends your session in this browser.</p>
        <button type="button" onClick={signOut}>Sign Out</button>
      </section>
    </div>
  );
}

/** The colour theme, remembered in this browser. */
function AppearanceTab() {
  const [theme, setChosen] = useState(getTheme);
  const choose = (value) => {
    setChosen(value);
    setTheme(value);
  };
  return (
    <div className="options-panel">
      <section>
        <h2>Theme</h2>
        <fieldset className="checkbox-group">
          <legend className="visually-hidden">Theme</legend>
          {THEMES.map((option) => (
            <label key={option.value} className="checkbox">
              <input type="radio" name="theme" value={option.value} checked={theme === option.value} onChange={() => choose(option.value)} />
              {option.label}
            </label>
          ))}
        </fieldset>
        <p className="muted small">Remembered in this browser only.</p>
      </section>
    </div>
  );
}

/** App-wide settings for admins: when time in a status counts as a bottleneck. */
function SystemTab() {
  const { thresholds, reload } = useSettings();
  const [values, setValues] = useState({
    awaitingReviewDays: String(thresholds.awaitingReviewDays), anyStatusDays: String(thresholds.anyStatusDays),
  });
  const [errors, setErrors] = useState({});
  const [submitError, setSubmitError] = useState();
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    setValues({
      awaitingReviewDays: String(thresholds.awaitingReviewDays), anyStatusDays: String(thresholds.anyStatusDays),
    });
  }, [thresholds]);

  const days = (value, label) => (/^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 365
    ? undefined : `${label} must be a whole number of days from 1 to 365`);
  const set = (field) => (event) => setValues((current) => ({ ...current, [field]: event.target.value }));

  const save = async (event) => {
    event.preventDefault();
    const found = compact({
      awaitingReviewDays: days(values.awaitingReviewDays, 'Awaiting review'),
      anyStatusDays: days(values.anyStatusDays, 'Any status'),
    });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSaving(true);
    setSaved(false);
    setSubmitError(undefined);
    try {
      await settingsApi.updateBottlenecks({
        awaitingReviewDays: Number(values.awaitingReviewDays), anyStatusDays: Number(values.anyStatusDays),
      });
      await reload();
      setSaved(true);
    } catch (error) {
      setSubmitError(error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="options-panel">
      <section>
        <h2>Bottleneck Thresholds</h2>
        <p className="muted">
          Projects and milestones are flagged when they sit in one status too long. Being still To do after the
          start date, or past the expected end date, is always flagged. These apply to everyone.
        </p>
        <form onSubmit={save} noValidate>
          {saved && <p className="success" role="status">Thresholds saved.</p>}
          <fieldset disabled={saving}>
            <div className="field-row">
              <Field id="threshold-review" label="Awaiting review for (days)" required error={errors.awaitingReviewDays} hint="Flags reviews a manager has not signed off.">
                {(props) => <input id={props.id} type="number" min="1" max="365" step="1" value={values.awaitingReviewDays} onChange={set('awaitingReviewDays')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
              </Field>
              <Field id="threshold-any" label="Any one status for (days)" required error={errors.anyStatusDays} hint="Flags anything unfinished that has not moved on.">
                {(props) => <input id={props.id} type="number" min="1" max="365" step="1" value={values.anyStatusDays} onChange={set('anyStatusDays')} aria-invalid={props.invalid} aria-describedby={props.describedBy} />}
              </Field>
            </div>
          </fieldset>
          <ErrorMessage error={submitError} />
          <div className="form-actions">
            <button type="submit" className="primary" disabled={saving}>{saving ? 'Saving…' : 'Save Thresholds'}</button>
          </div>
        </form>
      </section>
    </div>
  );
}

/**
 * Options, in tabs: Account (details, name and email, password, sign out), Appearance (theme) and, for admins,
 * System (app-wide settings).
 * @param {object} props Component props.
 * @param {string} [props.tab] The open tab, from the address; defaults to Account.
 * @returns {JSX.Element} The options page.
 */
function OptionsPage({ tab = 'account' }) {
  const { user } = useAuth();
  const tabs = tabsFor(user.role);
  const current = tabs.some((option) => option.id === tab) ? tab : 'account';

  const onKeyDown = (event) => {
    const index = tabs.findIndex((option) => option.id === current);
    const next = { ArrowRight: index + 1, ArrowLeft: index - 1 }[event.key];
    if (next !== undefined) {
      const target = tabs[(next + tabs.length) % tabs.length];
      navigate(`/options/${target.id}`);
      event.currentTarget.querySelector(`#options-tab-${target.id}`)?.focus();
    }
  };

  return (
    <main className="page">
      <div className="page-header">
        <h1>Options</h1>
      </div>
      <div className="tabs" role="tablist" aria-label="Options" onKeyDown={onKeyDown}>
        {tabs.map((option) => (
          <a
            key={option.id}
            id={`options-tab-${option.id}`}
            role="tab"
            href={`#/options/${option.id}`}
            aria-selected={option.id === current}
            aria-controls={`options-panel-${option.id}`}
            tabIndex={option.id === current ? 0 : -1}
          >
            {option.label}
          </a>
        ))}
      </div>
      <div role="tabpanel" id={`options-panel-${current}`} aria-labelledby={`options-tab-${current}`}>
        {current === 'account' && <AccountTab />}
        {current === 'appearance' && <AppearanceTab />}
        {current === 'system' && <SystemTab />}
      </div>
    </main>
  );
}

OptionsPage.propTypes = {
  tab: PropTypes.string,
};

export default OptionsPage;
