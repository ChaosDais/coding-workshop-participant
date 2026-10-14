import {
  createContext, useContext, useEffect, useMemo, useState,
} from 'react';
import PropTypes from 'prop-types';
import {
  auth, getSession, onSessionChange, refreshSession,
} from '../services/api.js';

/** Access tokens last 15 minutes; renewing every 10 keeps an open page signed in. */
const REFRESH_EVERY_MS = 10 * 60 * 1000;

const AuthContext = createContext(undefined);

/**
 * Provides the signed-in user and the sign-in, sign-out and password-change actions.
 * @param {object} props Component props.
 * @param {JSX.Element} props.children The app.
 * @returns {JSX.Element} The provider.
 */
export function AuthProvider({ children }) {
  const [session, setSession] = useState(getSession);

  useEffect(() => onSessionChange(setSession), []);

  useEffect(() => {
    if (!session?.refreshToken) {
      return undefined;
    }
    const timer = setInterval(() => refreshSession().catch(() => {}), REFRESH_EVERY_MS);
    return () => clearInterval(timer);
  }, [session?.refreshToken]);

  const value = useMemo(() => ({
    user: session?.user,
    signIn: auth.signIn,
    signOut: auth.signOut,
    changePassword: auth.changePassword,
  }), [session?.user]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

AuthProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

/** @returns {{user: object|undefined, signIn: Function, signOut: Function, changePassword: Function}} Auth. */
export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return value;
}
