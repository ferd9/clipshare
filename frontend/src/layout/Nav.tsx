import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import './layout.css';

export function Nav() {
  const { user, status, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate('/');
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
              <Link to="/upload">Subir clip</Link>
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
          Verificá tu email para poder publicar clips — en dev, el link queda en los logs del backend.
        </p>
      )}
    </header>
  );
}
