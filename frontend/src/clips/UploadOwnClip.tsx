import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { uploadClip } from './clipsApi';
import './clips.css';

const MAX_SOURCE_DURATION_MS = 10 * 60 * 1000;

// Con el email sin verificar igual se puede subir (limitado a 3/día — ver el banner en
// Nav.tsx, que ya avisa de esto en todas las páginas) — ver docs/SPEC.md sección 12.
export function UploadOwnClip() {
  const navigate = useNavigate();
  const videoRef = useRef<HTMLVideoElement>(null);

  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [durationMs, setDurationMs] = useState<number | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    setError(null);
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
      const result = await uploadClip(file, setProgress);
      // Después de subir hay que elegir recorte + silenciar/reemplazar audio (docs/SPEC.md
      // sección 9) — el worker todavía tiene que normalizar el archivo primero (fase STAGE),
      // ClipEditPage hace polling hasta que esté listo.
      navigate(`/clips/${result.id}/edit`);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo subir el clip'));
      setSubmitting(false);
    }
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

        {durationMs !== null && durationMs > MAX_SOURCE_DURATION_MS && (
          <p className="upload-hint">El video dura más de 10 minutos — no lo vamos a poder procesar.</p>
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
