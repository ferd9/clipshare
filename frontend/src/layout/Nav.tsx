import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import { extractErrorMessage, useAuth } from '../auth/AuthContext';
import './layout.css';

type ResendState = 'idle' | 'sending' | 'sent' | 'error';

export function Nav() {
  const { user, status, logout } = useAuth();
  const navigate = useNavigate();
  const [resendState, setResendState] = useState<ResendState>('idle');
  const [resendError, setResendError] = useState<string | null>(null);

  async function handleLogout() {
    await logout();
    navigate('/');
  }

  // Antes la única forma de conseguir un email de verificación nuevo era registrarse de
  // cero — imposible si la cuenta ya tiene contenido creado (clips), ver AuthService.
  // resendVerificationEmail.
  async function handleResendVerification() {
    setResendState('sending');
    setResendError(null);
    try {
      await apiClient.post('/api/users/me/resend-verification-email');
      setResendState('sent');
    } catch (err) {
      setResendState('error');
      setResendError(extractErrorMessage(err, 'No se pudo reenviar el email'));
    }
  }

  return (
    <header className="app-nav">
      <div className="app-nav-row">
        <Link to="/" className="app-nav-brand">
          ClipShare
        </Link>
        <nav className="app-nav-links">
          {status === 'authenticated' && user ? (
            <>
              <Link to="/upload">Nuevo clip</Link>
              {(user.role === 'ADMIN' || user.role === 'MODERATOR') && (
                <Link to="/admin/reports">Admin</Link>
              )}
              <span className="app-nav-user">{user.displayName}</span>
              <button type="button" onClick={() => void handleLogout()}>
                Cerrar sesión
              </button>
            </>
          ) : status === 'anonymous' ? (
            <>
              <Link to="/login">Iniciar sesión</Link>
              <Link to="/register">Registrarse</Link>
            </>
          ) : null}
        </nav>
      </div>
      {status === 'authenticated' && user && !user.emailVerified && (
        <p className="app-nav-banner">
          Con el email sin verificar podés crear hasta 3 clips por día — verificalo para subir
          sin límite.{' '}
          {resendState === 'sent' ? (
            'Te mandamos un email nuevo — revisá tu bandeja (y spam).'
          ) : (
            <button
              type="button"
              className="app-nav-banner-resend"
              onClick={() => void handleResendVerification()}
              disabled={resendState === 'sending'}
            >
              {resendState === 'sending' ? 'Enviando…' : 'Reenviar email de verificación'}
            </button>
          )}
          {resendState === 'error' && resendError && <span> — {resendError}</span>}
        </p>
      )}
    </header>
  );
}
