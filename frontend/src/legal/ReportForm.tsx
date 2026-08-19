import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { createReport, type ReportReason } from './reportsApi';
import './legal.css';

export function ReportForm() {
  const { clipId } = useParams<{ clipId: string }>();

  const [reason, setReason] = useState<ReportReason>('COPYRIGHT_DMCA');
  const [reporterName, setReporterName] = useState('');
  const [reporterEmail, setReporterEmail] = useState('');
  const [reporterAddress, setReporterAddress] = useState('');
  const [description, setDescription] = useState('');
  const [goodFaithStatement, setGoodFaithStatement] = useState(false);
  const [accuracyStatement, setAccuracyStatement] = useState(false);
  const [signature, setSignature] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const isDmca = reason === 'COPYRIGHT_DMCA';

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!clipId) return;
    setError(null);
    setSubmitting(true);
    try {
      await createReport({
        clipId,
        reason,
        reporterName: reporterName || undefined,
        reporterEmail,
        reporterAddress: reporterAddress || undefined,
        description: description || undefined,
        goodFaithStatement: isDmca ? goodFaithStatement : undefined,
        accuracyStatement: isDmca ? accuracyStatement : undefined,
        signature: signature || undefined,
      });
      setDone(true);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo enviar el reporte'));
    } finally {
      setSubmitting(false);
    }
  }

  if (!clipId) {
    return (
      <div className="legal-page">
        <p className="clips-error">
          Falta el clip a reportar — volvé al feed y usá el botón "Reportar" de un clip
          específico.
        </p>
        <Link to="/">← Volver al feed</Link>
      </div>
    );
  }

  if (done) {
    return (
      <div className="legal-page">
        <h1>Reporte enviado</h1>
        <p>Gracias — lo vamos a revisar. Si dejaste un email válido, te avisamos del resultado.</p>
        <Link to="/">← Volver al feed</Link>
      </div>
    );
  }

  return (
    <div className="legal-page">
      <h1>Reportar contenido</h1>
      <form className="legal-form" onSubmit={handleSubmit}>
        <label>
          Motivo
          <select value={reason} onChange={(event) => setReason(event.target.value as ReportReason)}>
            <option value="COPYRIGHT_DMCA">Copyright (DMCA)</option>
            <option value="CSAM">Explotación sexual infantil</option>
            <option value="HARASSMENT">Acoso</option>
            <option value="OTHER">Otro</option>
          </select>
        </label>

        <label>
          Tu nombre
          <input value={reporterName} onChange={(event) => setReporterName(event.target.value)} />
        </label>

        <label>
          Tu email (obligatorio)
          <input
            type="email"
            value={reporterEmail}
            onChange={(event) => setReporterEmail(event.target.value)}
            required
          />
        </label>

        {isDmca && (
          <label>
            Tu dirección (requerida en un aviso DMCA formal)
            <input
              value={reporterAddress}
              onChange={(event) => setReporterAddress(event.target.value)}
              required
            />
          </label>
        )}

        <label>
          Descripción
          <textarea
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            rows={4}
          />
        </label>

        {isDmca && (
          <>
            <label className="legal-checkbox">
              <input
                type="checkbox"
                checked={goodFaithStatement}
                onChange={(event) => setGoodFaithStatement(event.target.checked)}
                required
              />
              Declaro de buena fe que el uso del material no está autorizado por el dueño de
              los derechos, su agente o la ley.
            </label>
            <label className="legal-checkbox">
              <input
                type="checkbox"
                checked={accuracyStatement}
                onChange={(event) => setAccuracyStatement(event.target.checked)}
                required
              />
              Declaro, bajo pena de perjurio, que la información de este aviso es exacta y
              que soy el dueño de los derechos o estoy autorizado a actuar en su nombre.
            </label>
            <label>
              Firma (tu nombre completo)
              <input value={signature} onChange={(event) => setSignature(event.target.value)} required />
            </label>
          </>
        )}

        {error && (
          <p className="clips-error" role="alert">
            {error}
          </p>
        )}

        <button type="submit" disabled={submitting}>
          {submitting ? 'Enviando…' : 'Enviar reporte'}
        </button>
      </form>
    </div>
  );
}
