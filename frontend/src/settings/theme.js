const STORAGE_KEY = 'acme.theme';

/** The colour themes a user can pick; "system" follows the device setting. */
export const THEMES = [
  { value: 'system', label: 'Match my device' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
];

/** @returns {'system'|'light'|'dark'} The saved choice, or "system". */
export function getTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    return THEMES.some((theme) => theme.value === saved) ? saved : 'system';
  } catch {
    return 'system';
  }
}

/** Applies a theme to the page; the stylesheet reads data-theme. */
export function applyTheme(theme) {
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.dataset.theme = theme;
  } else {
    delete document.documentElement.dataset.theme;
  }
}

/** Saves and applies a theme. It is remembered in this browser only. */
export function setTheme(theme) {
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Storage can be unavailable; the theme then lasts until the page reloads.
  }
  applyTheme(theme);
}
