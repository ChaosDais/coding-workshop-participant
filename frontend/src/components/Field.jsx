import PropTypes from 'prop-types';

/**
 * A labelled form control with its validation message underneath.
 * @param {object} props Component props.
 * @param {string} props.id Control id, linking label, input and error.
 * @param {string} props.label Label text.
 * @param {boolean} [props.required] Marks the label as required.
 * @param {string} [props.error] Validation message.
 * @param {string} [props.hint] Help text.
 * @param {Function} props.children Renders the control from {id, invalid, describedBy}.
 * @returns {JSX.Element} The field.
 */
function Field({
  id, label, required = false, error, hint, children,
}) {
  const describedBy = [error && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(' ') || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required && <span className="required" aria-hidden="true"> *</span>}
      </label>
      {children({ id, invalid: Boolean(error), describedBy })}
      {hint && <p className="hint" id={`${id}-hint`}>{hint}</p>}
      {error && <p className="field-error" id={`${id}-error`}>{error}</p>}
    </div>
  );
}

Field.propTypes = {
  id: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
  required: PropTypes.bool,
  error: PropTypes.string,
  hint: PropTypes.string,
  children: PropTypes.func.isRequired,
};

export default Field;
