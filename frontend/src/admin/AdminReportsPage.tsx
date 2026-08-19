import { useEffect, useState } from 'react';
import { extractErrorMessage } from '../auth/AuthContext';
import { getPendingReports, resolveReport, type AdminReportSummary } from './adminApi';
import './admin.css';

const REASON_LABEL: Record<string, string> = {
  COPYRIGHT_DMCA: 'Copyright (DMCA)',
  CSAM: 'Explotación sexual infantil',
  HARASSMENT: 'Acoso',
  OTHER: 'Otro',
};

export function AdminReportsPage() {
  const [reports, setReports] = useState<AdminReportSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      const result = await getPendingReports();
      setReports(result.items);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudieron cargar los reportes'));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function handleAction(id: string, action: 'CONFIRMED' | 'DISMISSED') {
    setBusyId(id);
    setError(null);
    try {
      await resolveReport(id, action);
      setReports((current) => current?.filter((report) => report.id !== id) ?? null);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo resolver el reporte'));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="admin-page">
      <h1>Reportes pendientes</h1>
      {error && <p className="clips-error">{error}</p>}
      {reports === null && <p className="clips-loading">Cargando…</p>}
      {reports !== null && reports.length === 0 && <p>No hay reportes pendientes.</p>}

      <ul className="admin-report-list">
        {reports?.map((report) => (
          <li key={report.id} className="admin-report-card">
            <div className="admin-report-header">
              <span className="admin-report-reason">{REASON_LABEL[report.reason] ?? report.reason}</span>
              <span>{new Date(report.createdAt).toLocaleString()}</span>
            </div>
            <p className="admin-report-clip">Clip: {report.clipId}</p>
            <p>
              De: {report.reporterName ?? 'Anónimo'} ({report.reporterEmail})
            </p>
            {report.description && <p className="admin-report-description">{report.description}</p>}
            <div className="admin-report-actions">
              <button
                type="button"
                className="confirm"
                disabled={busyId === report.id}
                onClick={() => void handleAction(report.id, 'CONFIRMED')}
              >
                Confirmar y retirar
              </button>
              <button
                type="button"
                disabled={busyId === report.id}
                onClick={() => void handleAction(report.id, 'DISMISSED')}
              >
                Descartar
              </button>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
