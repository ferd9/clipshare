import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { detectPlatform, SUPPORTED_PLATFORMS } from './platformDetection';
import { importFromLink, uploadClip } from './clipsApi';
import './clips.css';

const MAX_SOURCE_DURATION_MS = 10 * 60 * 1000;

// Se arma solo a partir de SUPPORTED_PLATFORMS (única fuente) — agregar una plataforma ahí
// actualiza este texto sin tocar nada acá.
const SUPPORTED_PLATFORMS_LABEL = SUPPORTED_PLATFORMS.map((p) => p.label).join(', ');

/**
 * Reemplaza las dos pantallas separadas (UploadOwnClip + ImportFromLink, docs/SPEC.md
 * sección 9) por una sola: subir un archivo propio o importar desde un link son dos formas
 * de llegar a lo mismo (un clip en AWAITING_EDIT, ver ClipEditPage), así que conviven en la
 * misma pantalla en vez de forzar al usuario a elegir de antemano en el menú cuál de las dos
 * va a usar. Cada sección mantiene su propio estado — son independientes, se puede tocar
 * cualquiera de las dos sin que la otra interfiera.
 *
 * Con el email sin verificar igual se puede subir/importar (limitado a 3/día — ver el banner
 * en Nav.tsx) — ver docs/SPEC.md sección 12.
 */
export function NewClipPage() {
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);

  // --- Subir archivo propio ---
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [durationMs, setDurationMs] = useState<number | null>(null);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  // --- Importar desde un link ---
  const [url, setUrl] = useState('');
  const [importError, setImportError] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    event.target.value = ''; // permite elegir el mismo archivo de nuevo si se cancela después
    setUploadError(null);
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

  async function handleUploadSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file) return;
    setUploadError(null);
    setUploading(true);
    setUploadProgress(0);
    try {
      const result = await uploadClip(file, setUploadProgress);
      navigate(`/clips/${result.id}/edit`);
    } catch (err) {
      setUploadError(extractErrorMessage(err, 'No se pudo subir el clip'));
      setUploading(false);
    }
  }

  async function handleImportSubmit(event: FormEvent) {
    event.preventDefault();
    const detected = detectPlatform(url.trim());
    if (!detected) {
      setImportError(`No reconocemos ese link. Probá con una URL de: ${SUPPORTED_PLATFORMS_LABEL}.`);
      return;
    }
    setImportError(null);
    setImporting(true);
    try {
      const result = await importFromLink(url.trim(), detected.platform);
      navigate(`/clips/${result.id}/edit`);
    } catch (err) {
      setImportError(extractErrorMessage(err, 'No se pudo importar el video'));
      setImporting(false);
    }
  }

  return (
    <div className="new-clip-page">
      <h1>Nuevo clip</h1>

      <button
        type="button"
        className="new-clip-upload-zone"
        onClick={() => fileInputRef.current?.click()}
      >
        <span className="new-clip-upload-icon" aria-hidden="true">
          📤
        </span>
        <span className="new-clip-upload-label">{file ? file.name : 'Subir un video'}</span>
        <span className="new-clip-upload-hint">Elegí un archivo desde tu dispositivo — hasta 10 minutos</span>
      </button>
      <input
        ref={fileInputRef}
        type="file"
        accept="video/*"
        onChange={handleFileChange}
        style={{ display: 'none' }}
      />

      {file && (
        <form className="upload-form" onSubmit={handleUploadSubmit}>
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

          {uploadError && (
            <p className="clips-error" role="alert">
              {uploadError}
            </p>
          )}

          {uploading && uploadProgress !== null && (
            <div className="upload-progress">
              <div style={{ width: `${uploadProgress}%` }} />
            </div>
          )}

          <button type="submit" disabled={uploading}>
            {uploading ? `Subiendo… ${uploadProgress ?? 0}%` : 'Subir clip'}
          </button>
        </form>
      )}

      <div className="new-clip-divider">
        <span>o</span>
      </div>

      <form className="upload-form" onSubmit={(e) => void handleImportSubmit(e)}>
        {importError && (
          <p className="clips-error" role="alert">
            {importError}
          </p>
        )}
        <input
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="Pegá un link para importar — https://www.youtube.com/watch?v=..."
          required
          disabled={importing}
        />
        <button type="submit" disabled={importing}>
          {importing ? 'Importando…' : 'Importar desde un link'}
        </button>
        <p className="import-hint">
          Soportamos {SUPPORTED_PLATFORMS_LABEL}. El video de origen no puede superar los 10 minutos.
        </p>
      </form>
    </div>
  );
}
