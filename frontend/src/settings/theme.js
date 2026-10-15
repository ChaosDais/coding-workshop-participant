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

const FONT_SIZE_KEY = 'acme.fontSize';

/** Text sizes a user can pick; each scales the root font size, which the stylesheet's rem units follow. */
export const FONT_SIZES = [
  { value: 'small', label: 'Small', scale: '87.5%' },
  { value: 'default', label: 'Default', scale: '100%' },
  { value: 'large', label: 'Large', scale: '112.5%' },
  { value: 'larger', label: 'Larger', scale: '125%' },
];

/** @returns {string} The saved text size, or "default". */
export function getFontSize() {
  try {
    const saved = localStorage.getItem(FONT_SIZE_KEY);
    return FONT_SIZES.some((size) => size.value === saved) ? saved : 'default';
  } catch {
    return 'default';
  }
}

/** Applies a text size to the page; the stylesheet reads data-font-size. */
export function applyFontSize(size) {
  if (size && size !== 'default' && FONT_SIZES.some((option) => option.value === size)) {
    document.documentElement.dataset.fontSize = size;
  } else {
    delete document.documentElement.dataset.fontSize;
  }
}

/** Saves and applies a text size. It is remembered in this browser only. */
export function setFontSize(size) {
  try {
    localStorage.setItem(FONT_SIZE_KEY, size);
  } catch {
    // Storage can be unavailable; the size then lasts until the page reloads.
  }
  applyFontSize(size);
}
