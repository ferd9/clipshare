import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractErrorMessage, useAuth } from '../auth/AuthContext';
import { uploadClip } from './clipsApi';
import './clips.css';

const MAX_DURATION_MS = 20_000;

export function UploadOwnClip() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const videoRef = useRef<HTMLVideoElement>(null);

  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [durationMs, setDurationMs] = useState<number | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    setError(null);
    setDone(false);
    setDurationMs(null);
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setFile(selected);
    setPreviewUrl(selected ? URL.createObjectURL(selected) : null);
  }

  function handleLoadedMetadata() {
    const seconds = videoRef.current?.duration;
    if (seconds && Number.isFinite(seconds)) {
      setDurationMs(Math.round(seconds * 1000));
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file) return;
    setError(null);
    setSubmitting(true);
    setProgress(0);
    try {
      await uploadClip(file, setProgress);
      setDone(true);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo subir el clip'));
    } finally {
      setSubmitting(false);
    }
  }

  // email_verified_at es requisito para publicar (no para loguearse) — ver docs/SPEC.md sección 12.
  if (user && !user.emailVerified) {
    return (
      <div className="upload-page">
        <p className="clips-error">
          Verificá tu email antes de subir clips. En dev, el link de verificación queda en
          los logs del backend (LoggingEmailService).
        </p>
      </div>
    );
  }

  if (done) {
    return (
      <div className="upload-page">
        <p className="upload-success">
          ¡Listo! Tu clip se está procesando — en unos segundos aparece en el feed.
        </p>
        <button type="button" onClick={() => navigate('/')}>
          Ir al feed
        </button>
      </div>
    );
  }

  return (
    <div className="upload-page">
      <h1>Subir un clip</h1>
      <form className="upload-form" onSubmit={handleSubmit}>
        <input type="file" accept="video/*" onChange={handleFileChange} required />

        {previewUrl && (
          <video
            ref={videoRef}
            src={previewUrl}
            controls
            className="upload-preview"
            onLoadedMetadata={handleLoadedMetadata}
          />
        )}

        {durationMs !== null && durationMs > MAX_DURATION_MS && (
          <p className="upload-hint">
            Dura {(durationMs / 1000).toFixed(1)}s — el servidor recorta automáticamente a
            los primeros 20s.
          </p>
        )}

        {error && (
          <p className="clips-error" role="alert">
            {error}
          </p>
        )}

        {submitting && progress !== null && (
          <div className="upload-progress">
            <div style={{ width: `${progress}%` }} />
          </div>
        )}

        <button type="submit" disabled={!file || submitting}>
          {submitting ? `Subiendo… ${progress ?? 0}%` : 'Subir clip'}
        </button>
      </form>
    </div>
  );
}
