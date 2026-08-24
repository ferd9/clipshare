import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { LoginPage } from './auth/LoginPage';
import { RegisterPage } from './auth/RegisterPage';
import { AppShell } from './layout/AppShell';
import { ClipFeed } from './clips/ClipFeed';
import { UploadOwnClip } from './clips/UploadOwnClip';
import { ImportFromLink } from './clips/ImportFromLink';
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
          <Route element={<AppShell />}>
            <Route path="/" element={<ClipFeed />} />
            <Route path="/legal/dmca" element={<DmcaPage />} />
            <Route path="/report/:clipId" element={<ReportForm />} />
            <Route element={<ProtectedRoute />}>
              <Route path="/upload" element={<UploadOwnClip />} />
              <Route path="/import" element={<ImportFromLink />} />
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
