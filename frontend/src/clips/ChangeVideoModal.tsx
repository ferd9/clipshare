import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { detectPlatform, SUPPORTED_PLATFORMS } from './platformDetection';
import { importFromLink, uploadClip } from './clipsApi';
import './clips.css';

const SUPPORTED_PLATFORMS_LABEL = SUPPORTED_PLATFORMS.map((p) => p.label).join(', ');

interface ChangeVideoModalProps {
  open: boolean;
  onClose: () => void;
}

/**
 * Reemplazar el video que se está editando por uno distinto (subido o importado). A diferencia
 * del audio de reemplazo (ver AudioPicker), el video ES el clip, no un recurso aparte con su
 * propio id — "cambiarlo" arma un clip NUEVO igual que NewClipPage y navega derecho a su
 * propia edición, abandonando el draft actual (mismo destino que ya le toca hoy a cualquier
 * clip que se deja a medio editar sin publicar — no hace falta borrar nada del lado del
 * servidor). El remount completo de ClipEditPage al cambiar el :id de la URL (ver
 * ClipEditPageRoute) es lo que limpia el resto del estado (recorte, audio, historial de
 * "Sorprendeme") sin tener que resetear nada acá a mano.
 *
 * Mismo modal que AudioPicker (dropzone + link debajo) reusando sus mismas clases CSS — es
 * exactamente el mismo patrón visual, solo cambia a dónde van los datos.
 */
export function ChangeVideoModal({ open, onClose }: ChangeVideoModalProps) {
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [linkUrl, setLinkUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = ''; // permite elegir el mismo archivo de nuevo si falla
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const result = await uploadClip(file);
      navigate(`/clips/${result.id}/edit`, { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo subir el video'));
      setBusy(false);
    }
  }

  async function handleLinkSubmit(event: FormEvent) {
    event.preventDefault();
    const detected = detectPlatform(linkUrl.trim());
    if (!detected) {
      setError(`No reconocemos ese link. Probá con una URL de: ${SUPPORTED_PLATFORMS_LABEL}.`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await importFromLink(linkUrl.trim(), detected.platform);
      navigate(`/clips/${result.id}/edit`, { replace: true });
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo importar el video'));
      setBusy(false);
    }
  }

  if (!open) return null;

  return (
    <div className="audio-picker-modal-overlay" onClick={onClose}>
      <div className="audio-picker-modal" onClick={(event) => event.stopPropagation()}>
        <div className="audio-picker-modal-header">
          <h3>Cambiar video</h3>
          <button type="button" className="audio-picker-modal-close" onClick={onClose} aria-label="Cerrar">
            ×
          </button>
        </div>

        <p className="clip-trimmer-hint">
          Vas a empezar de nuevo con otro video — se pierde el recorte, el audio y el historial de sorteos actuales
          de este clip.
        </p>

        <button
          type="button"
          className="new-clip-upload-zone"
          onClick={() => fileInputRef.current?.click()}
          disabled={busy}
        >
          <span className="new-clip-upload-icon" aria-hidden="true">
            📤
          </span>
          <span className="new-clip-upload-label">Subir un video</span>
          <span className="new-clip-upload-hint">Elegí un archivo desde tu dispositivo — hasta 10 minutos</span>
        </button>
        {/* Oculto a propósito: el botón de arriba lo dispara directo, sin un input visible de
         * por medio, para que aparezca el selector nativo del sistema operativo enseguida. */}
        <input
          ref={fileInputRef}
          type="file"
          accept="video/*"
          onChange={(e) => void handleFileChange(e)}
          style={{ display: 'none' }}
        />

        <div className="new-clip-divider">
          <span>o</span>
        </div>

        <form className="audio-picker-form" onSubmit={(e) => void handleLinkSubmit(e)}>
          <input
            type="url"
            className="audio-picker-link-input"
            value={linkUrl}
            onChange={(event) => setLinkUrl(event.target.value)}
            placeholder="Pegá un link para importar — https://www.youtube.com/watch?v=..."
            required
            disabled={busy}
          />
          <button type="submit" disabled={busy || !linkUrl.trim()}>
            {busy ? 'Importando…' : 'Importar'}
          </button>
        </form>

        {busy && <p className="clips-loading">Un momento…</p>}
        {error && (
          <p className="clips-error" role="alert">
            {error}
          </p>
        )}
      </div>
    </div>
  );
}
