import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function AdminRoute() {
  const { user, status } = useAuth();

  if (status === 'loading') {
    return <p className="auth-loading">Cargando…</p>;
  }
  if (status === 'anonymous' || !user || (user.role !== 'ADMIN' && user.role !== 'MODERATOR')) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}
