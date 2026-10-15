import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';

/** After this long, a note explains that the server may be starting up. */
const SLOW_AFTER_MS = 5000;

/**
 * A spinner with what is loading, e.g. "Loading projects…". If it takes a while, adds that the server may be
 * waking up, which on AWS can take up to 30 seconds after a quiet spell.
 * @param {object} props Component props.
 * @param {string} props.children What is loading.
 * @returns {JSX.Element} The loading indicator.
 */
function Loading({ children }) {
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    const later = setTimeout(() => setSlow(true), SLOW_AFTER_MS);
    return () => clearTimeout(later);
  }, []);

  return (
    <div className="loading" role="status">
      <span className="spinner" aria-hidden="true" />
      <span>
        {children}
        {slow && <span className="muted small loading-slow">Still working: the server may be starting up, which can take up to 30 seconds.</span>}
      </span>
    </div>
  );
}

Loading.propTypes = {
  children: PropTypes.string.isRequired,
};

export default Loading;
