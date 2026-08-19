import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { extractErrorMessage, useAuth } from './AuthContext';
import './auth.css';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      navigate('/', { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo iniciar sesión'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-page">
      <form className="auth-form" onSubmit={handleSubmit}>
        <h1>Iniciar sesión</h1>
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
        <label>
          Contraseña
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            autoComplete="current-password"
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Ingresando…' : 'Ingresar'}
        </button>
        <p className="auth-switch">
          ¿No tenés cuenta? <Link to="/register">Registrate</Link>
        </p>
      </form>
    </div>
  );
}
