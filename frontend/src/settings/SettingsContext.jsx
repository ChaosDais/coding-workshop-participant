import {
  createContext, useCallback, useContext, useEffect, useMemo, useState,
} from 'react';
import PropTypes from 'prop-types';
import { settingsApi } from '../services/api.js';
import { DEFAULT_BOTTLENECK_THRESHOLDS } from '../utils/projects.js';

const SettingsContext = createContext({ thresholds: DEFAULT_BOTTLENECK_THRESHOLDS, reload: () => {} });

/**
 * Loads the app-wide settings once a user is signed in and shares them with every screen. Until they load, or if
 * they cannot be loaded, the defaults apply.
 * @param {object} props Component props.
 * @param {boolean} props.signedIn Whether to load them.
 * @param {JSX.Element} props.children The app.
 * @returns {JSX.Element} The provider.
 */
export function SettingsProvider({ signedIn, children }) {
  const [thresholds, setThresholds] = useState(DEFAULT_BOTTLENECK_THRESHOLDS);

  const reload = useCallback(async () => {
    try {
      setThresholds(await settingsApi.bottlenecks());
    } catch {
      setThresholds(DEFAULT_BOTTLENECK_THRESHOLDS);
    }
  }, []);

  useEffect(() => {
    if (signedIn) {
      reload();
    }
  }, [signedIn, reload]);

  const value = useMemo(() => ({ thresholds, reload }), [thresholds, reload]);
  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

SettingsProvider.propTypes = {
  signedIn: PropTypes.bool.isRequired,
  children: PropTypes.node.isRequired,
};

/** @returns {{thresholds: {awaitingReviewDays: number, anyStatusDays: number}, reload: Function}} Settings. */
export function useSettings() {
  return useContext(SettingsContext);
}
