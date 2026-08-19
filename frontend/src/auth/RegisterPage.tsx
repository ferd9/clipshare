import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { extractErrorMessage, useAuth } from './AuthContext';
import './auth.css';

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(email, password, displayName);
      navigate('/', { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo crear la cuenta'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-page">
      <form className="auth-form" onSubmit={handleSubmit}>
        <h1>Crear cuenta</h1>
        {error && (
          <p className="auth-error" role="alert">
            {error}
          </p>
        )}
        <label>
          Nombre
          <input
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            required
            maxLength={100}
            autoComplete="name"
          />
        </label>
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
            minLength={8}
            autoComplete="new-password"
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Creando cuenta…' : 'Crear cuenta'}
        </button>
        <p className="auth-switch">
          ¿Ya tenés cuenta? <Link to="/login">Iniciá sesión</Link>
        </p>
      </form>
    </div>
  );
}
