import { afterEach } from 'vitest';

// Pages remember settings (filters, page size) for the browser session; each test starts from a clean session.
afterEach(() => {
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.clear();
  }
});
