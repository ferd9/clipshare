import { useEffect, useRef, useState } from 'react';
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
  // El token se puede usar UNA sola vez (ver AuthService.verifyEmail) — sin este guard,
  // React.StrictMode (activo en dev, ver main.tsx) dispara el efecto dos veces al montar,
  // mandando el POST dos veces: la primera confirma la cuenta bien, la segunda encuentra el
  // token ya usado. Un ref (no un state) porque tiene que sobrevivir exactamente a ese doble
  // montaje-desmontaje-montaje, no solo a re-renders.
  //
  // A propósito SIN el patrón "cancelled" (banderita que la limpieza del efecto pone en true)
  // que se usa en el resto del código para descartar respuestas tardías: acá chocaba con este
  // guard — la limpieza del PRIMER montaje (el que de verdad manda el pedido) corre en el
  // segundo pase de StrictMode ANTES de que la respuesta vuelva, dejando "cancelled=true" para
  // siempre y el resultado exitoso nunca se aplicaba (pantalla colgada en "Verificando…").
  // Como requestedRef ya garantiza un solo pedido en toda la vida del componente, no hace
  // falta ese descarte acá.
  const requestedRef = useRef(false);

  useEffect(() => {
    if (!token) {
      setStatus('error');
      setError('Falta el token de verificación en el link.');
      return;
    }
    if (requestedRef.current) return;
    requestedRef.current = true;

    apiClient
      .post('/api/auth/verify-email', { token })
      .then(() => setStatus('success'))
      .catch((err) => {
        setStatus('error');
        setError(extractErrorMessage(err, 'No se pudo verificar la cuenta'));
      });
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
