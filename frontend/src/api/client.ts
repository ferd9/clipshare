import axios, { type InternalAxiosRequestConfig } from 'axios';

const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

// withCredentials: el backend corre en otro puerto que el frontend en dev (Vite), y la
// cookie anon_session_id (docs/SPEC.md sección 11.3) solo va y vuelve entre orígenes
// distintos si el navegador la manda explícitamente — sin esto, cada comentario de invitado
// parecería venir de una sesión anónima nueva.
export const apiClient = axios.create({ baseURL, withCredentials: true });

// El access token vive solo en memoria (nunca en localStorage) para acotar el daño de un
// XSS: como mucho robaría el token de la sesión actual, no algo persistente. AuthContext
// es quien lo actualiza cada vez que cambia.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

// AuthContext registra acá cómo pedir un access token nuevo con el refresh token guardado.
// Vive en client.ts (y no directamente en el componente) para poder engancharlo al
// interceptor de axios sin crear una dependencia circular AuthContext -> apiClient -> AuthContext.
type RefreshHandler = () => Promise<string | null>;
let refreshHandler: RefreshHandler | null = null;

export function setRefreshHandler(handler: RefreshHandler | null): void {
  refreshHandler = handler;
}

apiClient.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config as RetriableConfig | undefined;

    // Un 401 en el propio /api/auth/** (login/refresh con credenciales malas) no debe
    // reintentar: solo reintentamos llamadas a endpoints protegidos cuyo access token expiró.
    const isAuthEndpoint = original?.url?.startsWith('/api/auth/');

    if (error.response?.status === 401 && original && !original._retried && !isAuthEndpoint && refreshHandler) {
      original._retried = true;
      const newToken = await refreshHandler();
      if (newToken) {
        original.headers.Authorization = `Bearer ${newToken}`;
        return apiClient(original);
      }
    }

    return Promise.reject(error);
  },
);
