import PropTypes from 'prop-types';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

/**
 * Editable request: service, method, path, body and the remembered values (ids and tokens)
 * substituted into {placeholders} in the path and body.
 * @param {object} props Component props.
 * @param {{service: string, method: string, path: string, body: string}} props.request Current request.
 * @param {{name: string, label: string, hint: string}[]} props.fields Remembered values to show.
 * @param {Object<string, string>} props.values Current remembered values, by name.
 * @param {string[]} props.serviceNames Known service names offered as suggestions.
 * @param {string} props.url Fully resolved URL that will be called.
 * @param {boolean} props.sending Whether a request is in flight.
 * @param {Function} props.onChange Called with (field, value) for request edits.
 * @param {Function} props.onValueChange Called with (name, value) for remembered value edits.
 * @param {Function} props.onSend Called when the form is submitted.
 * @returns {JSX.Element} The request form.
 */
function RequestForm({
  request, fields, values, serviceNames, url, sending, onChange, onValueChange, onSend,
}) {
  return (
    <form
      className="request-form"
      onSubmit={(event) => {
        event.preventDefault();
        onSend();
      }}
    >
      <div className="row">
        <label htmlFor="method">
          Method
          <select id="method" value={request.method} onChange={(e) => onChange('method', e.target.value)}>
            {METHODS.map((method) => <option key={method}>{method}</option>)}
          </select>
        </label>
        <label htmlFor="service">
          Service
          <input
            id="service"
            list="service-names"
            value={request.service}
            onChange={(e) => onChange('service', e.target.value)}
          />
          <datalist id="service-names">
            {serviceNames.map((name) => <option key={name} value={name} />)}
          </datalist>
        </label>
        <label htmlFor="path" className="grow">
          Path
          <input id="path" value={request.path} onChange={(e) => onChange('path', e.target.value)} />
        </label>
      </div>

      <div className="row values">
        {fields.map((field) => (
          <label key={field.name} htmlFor={`value-${field.name}`} className="grow">
            {`${field.label} {${field.name}}`}
            <input
              id={`value-${field.name}`}
              value={values[field.name]}
              placeholder={field.hint}
              spellCheck={false}
              onChange={(e) => onValueChange(field.name, e.target.value)}
            />
          </label>
        ))}
      </div>

      <label htmlFor="body">
        JSON body
        <textarea
          id="body"
          rows={12}
          spellCheck={false}
          value={request.body}
          onChange={(e) => onChange('body', e.target.value)}
        />
      </label>

      <div className="row send-row">
        <code className="url">{url}</code>
        <button type="submit" disabled={sending}>{sending ? 'Sending…' : 'Send'}</button>
      </div>
    </form>
  );
}

RequestForm.propTypes = {
  request: PropTypes.shape({
    service: PropTypes.string.isRequired,
    method: PropTypes.string.isRequired,
    path: PropTypes.string.isRequired,
    body: PropTypes.string.isRequired,
  }).isRequired,
  fields: PropTypes.arrayOf(PropTypes.shape({
    name: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    hint: PropTypes.string.isRequired,
  })).isRequired,
  values: PropTypes.objectOf(PropTypes.string).isRequired,
  serviceNames: PropTypes.arrayOf(PropTypes.string).isRequired,
  url: PropTypes.string.isRequired,
  sending: PropTypes.bool.isRequired,
  onChange: PropTypes.func.isRequired,
  onValueChange: PropTypes.func.isRequired,
  onSend: PropTypes.func.isRequired,
};

export default RequestForm;
