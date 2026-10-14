import PropTypes from 'prop-types';

/**
 * An error banner, read out by screen readers when it appears.
 * @param {object} props Component props.
 * @param {Error|string|undefined} props.error The error; nothing renders without one.
 * @param {Function} [props.onRetry] Shows a Try again button.
 * @returns {JSX.Element|null} The banner.
 */
function ErrorMessage({ error, onRetry }) {
  if (!error) {
    return null;
  }
  return (
    <div className="error-banner" role="alert">
      <span>{typeof error === 'string' ? error : error.message}</span>
      {onRetry && <button type="button" onClick={onRetry}>Try again</button>}
    </div>
  );
}

ErrorMessage.propTypes = {
  error: PropTypes.oneOfType([PropTypes.string, PropTypes.instanceOf(Error)]),
  onRetry: PropTypes.func,
};

export default ErrorMessage;
