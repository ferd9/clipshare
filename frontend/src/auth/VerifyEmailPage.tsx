import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { extractErrorMessage } from './AuthContext';
import './auth.css';

type Status = 'verifying' | 'success' | 'error';

/**
 * Destino del link que manda el email real de verificación (ver ResendEmailService.
 * sendVerificationEmail) — confirma el token automáticamente al abrir el link, sin que el
 * usuario tenga que hacer nada más que hacer click. Antes de esto no existía ninguna pantalla
 * que consumiera ese token: el "email" (LoggingEmailService, dev) solo dejaba el token crudo
 * en los logs.
 */
export function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [status, setStatus] = useState<Status>('verifying');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setStatus('error');
      setError('Falta el token de verificación en el link.');
      return;
    }
    let cancelled = false;
    apiClient
      .post('/api/auth/verify-email', { token })
      .then(() => {
        if (!cancelled) setStatus('success');
      })
      .catch((err) => {
        if (!cancelled) {
          setStatus('error');
          setError(extractErrorMessage(err, 'No se pudo verificar la cuenta'));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  return (
    <div className="auth-page">
      <div className="auth-form">
        <h1>Verificación de cuenta</h1>

        {status === 'verifying' && <p className="clips-loading">Verificando tu cuenta…</p>}

        {status === 'success' && (
          <>
            <p>¡Listo! Tu cuenta quedó verificada.</p>
            <p className="auth-switch">
              <Link to="/login">Iniciar sesión</Link>
            </p>
          </>
        )}

        {status === 'error' && (
          <>
            <p className="auth-error" role="alert">
              {error}
            </p>
            <p className="auth-switch">
              <Link to="/">Volver al inicio</Link>
            </p>
          </>
        )}
      </div>
    </div>
  );
}
