import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { DataMode, User } from './types';
import { auth, createCatalog, hasLoginSession } from './lib/api';
import { useLocation, useNavigate } from 'react-router-dom';
import { createCommunity } from './lib/community';

function useAppState() {
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, updateMode] = useState<DataMode>(() => {
    const param = new URLSearchParams(window.location.search).get('preview');
    if (param !== null) return param === '1' ? 'preview' : 'live';
    return sessionStorage.getItem('hmdp-mode') === 'preview' ? 'preview' : 'live';
  });
  const [user, setUser] = useState<User | null>(null);
  const [authLoading, setAuthLoading] = useState(hasLoginSession);
  const [sessionRevision, setSessionRevision] = useState(0);
  const [loginOpen, setLoginOpen] = useState(false);
  const [theme, updateTheme] = useState(() => document.documentElement.dataset.theme || 'light');
  const setMode = useCallback((value: DataMode) => {
    updateMode(value);
    const params = new URLSearchParams(location.search);
    params.delete('preview');
    navigate({ pathname: location.pathname, search: params.toString(), hash: location.hash }, { replace: true });
  }, [navigate, location.pathname, location.search, location.hash]);
  useEffect(() => { sessionStorage.setItem('hmdp-mode', mode); }, [mode]);
  useEffect(() => {
    let active = true;
    if (mode === 'live' && hasLoginSession()) {
      setAuthLoading(true);
      auth.me().then((user) => { if (active) setUser(user); }).catch(() => { if (active) setUser(null); }).finally(() => { if (active) setAuthLoading(false); });
    } else { setUser(null); setAuthLoading(false); }
    return () => { active = false; };
  }, [mode, sessionRevision]);
  useEffect(() => {
    const handler = () => { setUser(null); setLoginOpen(true); };
    const signedOut = () => { setUser(null); setAuthLoading(false); setLoginOpen(false); };
    const changed = () => { setUser(null); setSessionRevision((value) => value + 1); };
    window.addEventListener('hmdp:unauthorized', handler);
    window.addEventListener('hmdp:signed-out', signedOut);
    window.addEventListener('hmdp:session-changed', changed);
    return () => {
      window.removeEventListener('hmdp:unauthorized', handler);
      window.removeEventListener('hmdp:signed-out', signedOut);
      window.removeEventListener('hmdp:session-changed', changed);
    };
  }, []);
  const toggleTheme = () => {
    const value = theme === 'light' ? 'dark' : 'light';
    document.documentElement.dataset.theme = value;
    localStorage.setItem('hmdp-theme', value);
    updateTheme(value);
  };
  const catalog = useMemo(() => createCatalog(mode), [mode]);
  const community = useMemo(() => createCommunity(mode), [mode]);
  return { mode, setMode, catalog, community, user, setUser, authLoading, loginOpen, setLoginOpen, theme, toggleTheme };
}

const AppContext = createContext<ReturnType<typeof useAppState> | null>(null);
export function AppProvider({ children }: { children: ReactNode }) {
  return <AppContext.Provider value={useAppState()}>{children}</AppContext.Provider>;
}
export function useApp() {
  const context = useContext(AppContext);
  if (!context) throw new Error('AppProvider is required');
  return context;
}
