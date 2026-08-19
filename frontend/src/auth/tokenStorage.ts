// Solo el refresh token se persiste (localStorage), para poder restaurar la sesión al
// recargar la página sin pedir login de nuevo. El access token vive en memoria (ver api/client.ts).
const REFRESH_TOKEN_KEY = 'clipshare.refreshToken';

export function getStoredRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setStoredRefreshToken(token: string): void {
  localStorage.setItem(REFRESH_TOKEN_KEY, token);
}

export function clearStoredRefreshToken(): void {
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}
