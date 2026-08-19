import { useAuth } from './AuthContext';
import './auth.css';

// Placeholder para probar el flujo de auth end-to-end (Fase 1). Fase 2 lo reemplaza
// por ClipFeed.tsx como pantalla principal (ver docs/SPEC.md sección 14).
export function HomePage() {
  const { user, logout } = useAuth();
  if (!user) return null;

  return (
    <div className="home-page">
      <h1>Hola, {user.displayName} 👋</h1>
      <dl>
        <dt>Email</dt>
        <dd>{user.email}</dd>
        <dt>Estado de la cuenta</dt>
        <dd>{user.status}</dd>
        <dt>Email verificado</dt>
        <dd>{user.emailVerified ? 'Sí' : 'No'}</dd>
      </dl>
      {!user.emailVerified && (
        <p className="auth-hint">
          Todavía no verificaste tu email. En dev, el link de verificación queda en los
          logs del backend (LoggingEmailService) — no hay envío real de correo todavía.
        </p>
      )}
      <button type="button" onClick={() => void logout()}>
        Cerrar sesión
      </button>
    </div>
  );
}
