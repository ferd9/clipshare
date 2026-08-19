import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './AuthContext';

export function ProtectedRoute() {
  const { status } = useAuth();

  if (status === 'loading') {
    return <p className="auth-loading">Cargando…</p>;
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace />;
  }
  return <Outlet />;
}
