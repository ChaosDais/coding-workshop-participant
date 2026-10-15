import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';

/**
 * Moves between pages: first, previous, a page number to type in, next and last. The typed number is applied on
 * Enter or when leaving the field, kept within 1 and the page count.
 * @param {object} props Component props.
 * @param {number} props.page Current page, from 1.
 * @param {number} props.pages How many pages there are.
 * @param {Function} props.onChange Receives the page to show.
 * @param {string} props.label What is paged, for screen readers, e.g. "Audit log pages, top".
 * @param {boolean} [props.disabled] Disables everything, e.g. while a page loads.
 * @returns {JSX.Element|null} The controls, or nothing when everything fits on one page.
 */
function Pagination({
  page, pages, onChange, label, disabled = false,
}) {
  const [typed, setTyped] = useState(String(page));
  useEffect(() => setTyped(String(page)), [page]);

  if (pages <= 1) {
    return null;
  }
  const go = (target) => {
    const next = Math.min(Math.max(1, target), pages);
    setTyped(String(next));
    if (next !== page) {
      onChange(next);
    }
  };
  const applyTyped = () => {
    const number = Number.parseInt(typed, 10);
    if (Number.isNaN(number)) {
      setTyped(String(page));
    } else {
      go(number);
    }
  };
  const first = page <= 1;
  const last = page >= pages;

  return (
    <nav className="pagination" aria-label={label}>
      <button type="button" onClick={() => go(1)} disabled={disabled || first} aria-label="First page" title="First page">«</button>
      <button type="button" onClick={() => go(page - 1)} disabled={disabled || first} aria-label="Previous page" title="Previous page">‹</button>
      <label className="pagination-page">
        Page
        <input
          type="number"
          min="1"
          max={pages}
          value={typed}
          disabled={disabled}
          aria-label="Page number"
          onChange={(event) => setTyped(event.target.value)}
          onBlur={applyTyped}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              applyTyped();
            }
          }}
        />
        {`of ${pages}`}
      </label>
      <button type="button" onClick={() => go(page + 1)} disabled={disabled || last} aria-label="Next page" title="Next page">›</button>
      <button type="button" onClick={() => go(pages)} disabled={disabled || last} aria-label="Last page" title="Last page">»</button>
    </nav>
  );
}

Pagination.propTypes = {
  page: PropTypes.number.isRequired,
  pages: PropTypes.number.isRequired,
  onChange: PropTypes.func.isRequired,
  label: PropTypes.string.isRequired,
  disabled: PropTypes.bool,
};

export default Pagination;
