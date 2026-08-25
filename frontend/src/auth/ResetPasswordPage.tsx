import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { extractErrorMessage } from './AuthContext';
import './auth.css';

/**
 * Destino del link que manda el email de reset (ver ResendEmailService.
 * sendPasswordResetEmail) — antes no existía ninguna pantalla que consumiera ese token, quedaba
 * muerto apenas se mandaba el mail real.
 */
export function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [newPassword, setNewPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!token) return;
    setError(null);
    setSubmitting(true);
    try {
      await apiClient.post('/api/auth/password-reset/confirm', { token, newPassword });
      setDone(true);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo restablecer la contraseña'));
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <div className="auth-page">
        <div className="auth-form">
          <h1>Restablecer contraseña</h1>
          <p>¡Listo! Tu contraseña se actualizó — ya podés iniciar sesión con la nueva.</p>
          <p className="auth-switch">
            <Link to="/login">Iniciar sesión</Link>
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-page">
      <form className="auth-form" onSubmit={handleSubmit}>
        <h1>Restablecer contraseña</h1>
        {!token && (
          <p className="auth-error" role="alert">
            Falta el token de recuperación en el link.
          </p>
        )}
        {error && (
          <p className="auth-error" role="alert">
            {error}
          </p>
        )}
        <label>
          Contraseña nueva
          <input
            type="password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            required
            minLength={8}
            autoComplete="new-password"
          />
        </label>
        <button type="submit" disabled={submitting || !token}>
          {submitting ? 'Guardando…' : 'Restablecer contraseña'}
        </button>
      </form>
    </div>
  );
}
