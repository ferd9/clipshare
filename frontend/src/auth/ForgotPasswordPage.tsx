import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import { extractErrorMessage } from './AuthContext';
import './auth.css';

/**
 * Punto de entrada para pedir un reset de contraseña (docs/SPEC.md sección 12) — antes no
 * existía ningún lugar del frontend desde donde dispararlo (AuthService.requestPasswordReset
 * ya funcionaba del lado del backend, pero nada lo llamaba). Siempre muestra el mismo mensaje
 * de éxito exista o no la cuenta — el backend nunca revela si el email está registrado (evita
 * que esto sirva para adivinar cuentas), así que la pantalla tampoco puede distinguirlo.
 */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await apiClient.post('/api/auth/password-reset/request', { email });
      setSent(true);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo procesar el pedido'));
    } finally {
      setSubmitting(false);
    }
  }

  if (sent) {
    return (
      <div className="auth-page">
        <div className="auth-form">
          <h1>Recuperar contraseña</h1>
          <p>Si existe una cuenta con ese email, te mandamos un link para restablecer la contraseña.</p>
          <p className="auth-switch">
            <Link to="/login">Volver a iniciar sesión</Link>
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-page">
      <form className="auth-form" onSubmit={handleSubmit}>
        <h1>Recuperar contraseña</h1>
        {error && (
          <p className="auth-error" role="alert">
            {error}
          </p>
        )}
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
            autoComplete="email"
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Enviando…' : 'Mandar link de recuperación'}
        </button>
        <p className="auth-switch">
          <Link to="/login">Volver a iniciar sesión</Link>
        </p>
      </form>
    </div>
  );
}
