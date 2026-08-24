import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { detectPlatform, SUPPORTED_PLATFORMS } from './platformDetection';
import { importFromLink } from './clipsApi';
import './clips.css';

// Se arma solo a partir de SUPPORTED_PLATFORMS (única fuente) — agregar una plataforma ahí
// actualiza este texto y el de más abajo sin tocar nada acá.
const SUPPORTED_PLATFORMS_LABEL = SUPPORTED_PLATFORMS.map((p) => p.label).join(', ');

// Con el email sin verificar igual se puede importar (limitado a 3/día — ver el banner
// en Nav.tsx, que ya avisa de esto en todas las páginas) — ver docs/SPEC.md sección 12.
//
// Reemplaza al viejo flujo de grabación de pantalla (getDisplayMedia + MediaRecorder,
// retirado por calidad inaceptable — ver docs/SPEC.md): ahora el video se descarga
// server-side vía yt-dlp, acá solo se manda la URL.
export function ImportFromLink() {
  const navigate = useNavigate();
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const detected = detectPlatform(url.trim());
    if (!detected) {
      setError(`No reconocemos ese link. Probá con una URL de: ${SUPPORTED_PLATFORMS_LABEL}.`);
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      const result = await importFromLink(url.trim(), detected.platform);
      // El worker todavía tiene que descargar y normalizar el video (fase STAGE, puede tardar
      // según la duración de la fuente) — ClipEditPage hace polling hasta que esté listo para
      // elegir recorte + silenciar/reemplazar audio.
      navigate(`/clips/${result.id}/edit`);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudo importar el video'));
      setSubmitting(false);
    }
  }

  return (
    <div className="upload-page">
      <h1>Importar desde un link</h1>
      <form className="upload-form" onSubmit={(e) => void handleSubmit(e)}>
        {error && (
          <p className="clips-error" role="alert">
            {error}
          </p>
        )}
        <input
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://www.youtube.com/watch?v=..."
          required
          disabled={submitting}
        />
        <button type="submit" disabled={submitting}>
          {submitting ? 'Importando…' : 'Importar'}
        </button>
        <p className="import-hint">
          Soportamos {SUPPORTED_PLATFORMS_LABEL}. El video de origen no puede superar los 10 minutos.
        </p>
      </form>
    </div>
  );
}
