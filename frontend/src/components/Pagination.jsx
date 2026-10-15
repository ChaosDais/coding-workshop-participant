import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';

/**
 * Moves between pages: first, previous, a page number to type in, next and last. A typed number is applied with
 * Go, Enter or on leaving the field, kept within 1 and the page count; the field's arrows and arrow keys move
 * straight away. Outside of typing, the field always shows the current page.
 * @param {object} props Component props.
 * @param {number} props.page Current page, from 1.
 * @param {number} props.pages How many pages there are.
 * @param {Function} props.onChange Receives the page to show.
 * @param {string} props.label What is paged, for screen readers, e.g. "Audit log pages, top".
 * @param {boolean} [props.disabled] Disables the buttons, e.g. while a page loads.
 * @returns {JSX.Element|null} The controls, or nothing when everything fits on one page.
 */
function Pagination({
  page, pages, onChange, label, disabled = false,
}) {
  // What is being typed; undefined when not editing, so the field shows the current page.
  const [typed, setTyped] = useState();
  // The page last asked for, so quick repeated steps add up before the new page has rendered.
  const requested = useRef(page);
  useEffect(() => {
    requested.current = page;
  }, [page]);

  if (pages <= 1) {
    return null;
  }
  const go = (target) => {
    setTyped(undefined);
    const next = Math.min(Math.max(1, target), pages);
    if (next !== requested.current) {
      requested.current = next;
      onChange(next);
    }
  };
  const applyTyped = () => {
    if (typed === undefined) {
      return;
    }
    const number = Number.parseInt(typed, 10);
    if (Number.isNaN(number)) {
      setTyped(undefined);
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
      {/* noValidate: a number past the end is clamped to the last page, not refused by the browser. */}
      <form
        className="pagination-page"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          applyTyped();
        }}
      >
        <label>
          Page
          <input
            type="number"
            min="1"
            max={pages}
            value={typed ?? String(page)}
            aria-label="Page number"
            onChange={(event) => {
              // The field's arrows step the number with no inputType; typing has one. Steps move at once.
              if (!event.nativeEvent.inputType && event.target.value !== '') {
                go(Number(event.target.value));
              } else {
                setTyped(event.target.value);
              }
            }}
            onKeyDown={(event) => {
              if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
                event.preventDefault();
                go(requested.current + (event.key === 'ArrowUp' ? 1 : -1));
              }
            }}
            onBlur={applyTyped}
          />
          {`of ${pages}`}
        </label>
        <button type="submit" disabled={disabled}>Go</button>
      </form>
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
