import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { LoginPage } from './auth/LoginPage';
import { RegisterPage } from './auth/RegisterPage';
import { VerifyEmailPage } from './auth/VerifyEmailPage';
import { ForgotPasswordPage } from './auth/ForgotPasswordPage';
import { ResetPasswordPage } from './auth/ResetPasswordPage';
import { AppShell } from './layout/AppShell';
import { ClipFeed } from './clips/ClipFeed';
import { NewClipPage } from './clips/NewClipPage';
import { ClipEditPage } from './clips/ClipEditPage';
import { DmcaPage } from './legal/DmcaPage';
import { ReportForm } from './legal/ReportForm';
import { AdminRoute } from './admin/AdminRoute';
import { AdminReportsPage } from './admin/AdminReportsPage';

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route element={<AppShell />}>
            <Route path="/" element={<ClipFeed />} />
            <Route path="/legal/dmca" element={<DmcaPage />} />
            <Route path="/report/:clipId" element={<ReportForm />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/upload" element={<NewClipPage />} />
              {/* /import queda como alias por si hay algún link viejo guardado — la pantalla
               * ahora es una sola (ver NewClipPage). */}
              <Route path="/import" element={<Navigate to="/upload" replace />} />
              <Route path="/clips/:id/edit" element={<ClipEditPage />} />
            </Route>
            <Route element={<AdminRoute />}>
              <Route path="/admin/reports" element={<AdminReportsPage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
