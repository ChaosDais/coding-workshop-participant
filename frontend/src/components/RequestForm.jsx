import PropTypes from 'prop-types';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

/**
 * Editable request: service, method, path, body and the ids substituted into the path.
 * @param {object} props Component props.
 * @param {{service: string, method: string, path: string, body: string}} props.request Current request.
 * @param {{projectId: string, milestoneId: string}} props.ids Ids for path placeholders.
 * @param {string[]} props.serviceNames Known service names offered as suggestions.
 * @param {string} props.url Fully resolved URL that will be called.
 * @param {boolean} props.sending Whether a request is in flight.
 * @param {Function} props.onChange Called with (field, value) for request edits.
 * @param {Function} props.onIdChange Called with (name, value) for id edits.
 * @param {Function} props.onSend Called when the form is submitted.
 * @returns {JSX.Element} The request form.
 */
function RequestForm({
  request, ids, serviceNames, url, sending, onChange, onIdChange, onSend,
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

      <div className="row">
        <label htmlFor="project-id" className="grow">
          Project id
          <input
            id="project-id"
            value={ids.projectId}
            placeholder="filled in after Create project"
            onChange={(e) => onIdChange('projectId', e.target.value)}
          />
        </label>
        <label htmlFor="milestone-id" className="grow">
          Milestone id
          <input
            id="milestone-id"
            value={ids.milestoneId}
            placeholder="filled in after Add milestone"
            onChange={(e) => onIdChange('milestoneId', e.target.value)}
          />
        </label>
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
  ids: PropTypes.shape({
    projectId: PropTypes.string.isRequired,
    milestoneId: PropTypes.string.isRequired,
  }).isRequired,
  serviceNames: PropTypes.arrayOf(PropTypes.string).isRequired,
  url: PropTypes.string.isRequired,
  sending: PropTypes.bool.isRequired,
  onChange: PropTypes.func.isRequired,
  onIdChange: PropTypes.func.isRequired,
  onSend: PropTypes.func.isRequired,
};

export default RequestForm;
