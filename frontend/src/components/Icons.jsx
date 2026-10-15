/** Small inline icons; decorative, so the control using one must carry its own label. */

export function MagnifierIcon() {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">
      <path fill="currentColor" d="M10 2a8 8 0 0 1 6.32 12.9l5.39 5.4-1.41 1.4-5.4-5.39A8 8 0 1 1 10 2Zm0 2a6 6 0 1 0 0 12 6 6 0 0 0 0-12Z" />
    </svg>
  );
}

export function ChevronIcon() {
  return (
    <svg className="chevron" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false">
      <path fill="currentColor" d="M9 6l6 6-6 6-1.4-1.4 4.6-4.6-4.6-4.6L9 6Z" />
    </svg>
  );
}
