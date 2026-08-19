import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import axios from 'axios';
import { apiClient, setAccessToken, setRefreshHandler } from '../api/client';
import { clearStoredRefreshToken, getStoredRefreshToken, setStoredRefreshToken } from './tokenStorage';
import type { AuthTokens, UserProfile } from './types';

type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  user: UserProfile | null;
  status: AuthStatus;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function extractErrorMessage(err: unknown, fallback = 'Ocurrió un error inesperado'): string {
  if (axios.isAxiosError(err) && err.response?.data && typeof err.response.data === 'object') {
    const body = err.response.data as { message?: string };
    if (body.message) return body.message;
  }
  return fallback;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');

  const applyTokens = useCallback((tokens: AuthTokens) => {
    setAccessToken(tokens.accessToken);
    setStoredRefreshToken(tokens.refreshToken);
  }, []);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    clearStoredRefreshToken();
    setUser(null);
    setStatus('anonymous');
  }, []);

  const fetchProfile = useCallback(async () => {
    const { data } = await apiClient.get<UserProfile>('/api/users/me');
    setUser(data);
    setStatus('authenticated');
  }, []);

  // Único punto que sabe pedir un access token nuevo con el refresh token guardado.
  // Se usa tanto al cargar la página (restaurar sesión) como desde el interceptor de
  // axios cuando un access token expira en medio de un request (ver api/client.ts).
  const refresh = useCallback(async (): Promise<string | null> => {
    const storedRefreshToken = getStoredRefreshToken();
    if (!storedRefreshToken) return null;

    try {
      const { data } = await apiClient.post<AuthTokens>('/api/auth/refresh', {
        refreshToken: storedRefreshToken,
      });
      applyTokens(data);
      return data.accessToken;
    } catch {
      clearSession();
      return null;
    }
  }, [applyTokens, clearSession]);

  useEffect(() => {
    setRefreshHandler(refresh);
    return () => setRefreshHandler(null);
  }, [refresh]);

  useEffect(() => {
    (async () => {
      const newAccessToken = await refresh();
      if (!newAccessToken) {
        setStatus('anonymous');
        return;
      }
      try {
        await fetchProfile();
      } catch {
        clearSession();
      }
    })();
    // Solo al montar: refresh/fetchProfile ya están memoizados con useCallback.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const { data } = await apiClient.post<AuthTokens>('/api/auth/login', { email, password });
    applyTokens(data);
    await fetchProfile();
  }, [applyTokens, fetchProfile]);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    await apiClient.post('/api/auth/register', { email, password, displayName });
    // El registro no devuelve tokens (el usuario queda PENDING_VERIFICATION); logueamos
    // directo para no pedirle la contraseña dos veces seguidas.
    await login(email, password);
  }, [login]);

  const logout = useCallback(async () => {
    const storedRefreshToken = getStoredRefreshToken();
    if (storedRefreshToken) {
      try {
        await apiClient.post('/api/auth/logout', { refreshToken: storedRefreshToken });
      } catch {
        // Best-effort: si falla igual limpiamos la sesión local.
      }
    }
    clearSession();
  }, [clearSession]);

  const value = useMemo<AuthContextValue>(
    () => ({ user, status, login, register, logout }),
    [user, status, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth debe usarse dentro de <AuthProvider>');
  return ctx;
}
