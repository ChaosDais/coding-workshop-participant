import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * A dialog over the page. Escape or the backdrop closes it, and focus moves into it when it opens.
 * @param {object} props Component props.
 * @param {string} props.title Heading, also the dialog's accessible name.
 * @param {Function} props.onClose Closes the dialog.
 * @param {JSX.Element} props.children Content.
 * @returns {JSX.Element} The dialog.
 */
function Modal({ title, onClose, children }) {
  const panel = useRef(null);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    panel.current?.querySelector('input, select, textarea, button')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title} ref={panel}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button type="button" className="link-button" onClick={onClose} aria-label="Close">✕</button>
        </div>
        {children}
      </div>
    </div>
  );
}

Modal.propTypes = {
  title: PropTypes.string.isRequired,
  onClose: PropTypes.func.isRequired,
  children: PropTypes.node.isRequired,
};

export default Modal;
