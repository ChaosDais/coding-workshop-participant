import PropTypes from 'prop-types';
import { parseJson } from '../services/apiClient.js';

/**
 * Shows the status, timing and body of the last response, pretty-printing JSON.
 * @param {object} props Component props.
 * @param {object} [props.response] Result from sendRequest, or undefined before the first send.
 * @returns {JSX.Element} The response panel.
 */
function ResponsePanel({ response }) {
  if (!response) {
    return <p className="muted">Pick a preset and press Send to see the response here.</p>;
  }

  const parsed = parseJson(response.body);
  const text = parsed === undefined ? response.body : JSON.stringify(parsed, null, 2);
  const label = response.error ? 'Network error' : `${response.status} ${response.statusText}`.trim();

  return (
    <div className="response">
      <p>
        <strong className={response.ok ? 'ok' : 'fail'}>{label}</strong>
        <span className="muted">{` · ${response.durationMs} ms`}</span>
      </p>
      <pre>{response.error || text || '(empty body)'}</pre>
    </div>
  );
}

ResponsePanel.propTypes = {
  response: PropTypes.shape({
    status: PropTypes.number.isRequired,
    statusText: PropTypes.string.isRequired,
    ok: PropTypes.bool.isRequired,
    durationMs: PropTypes.number.isRequired,
    body: PropTypes.string.isRequired,
    error: PropTypes.string,
  }),
};

export default ResponsePanel;
