import { useState, type FormEvent } from 'react';
import { ClipEditor } from './ClipEditor';
import { detectPlatform } from './platformDetection';
import './clips.css';

// Con el email sin verificar igual se puede importar (limitado a 3/día — ver el banner
// en Nav.tsx, que ya avisa de esto en todas las páginas) — ver docs/SPEC.md sección 12.
export function ImportFromLink() {
  const [url, setUrl] = useState('');
  const [confirmed, setConfirmed] = useState<{ url: string; platform: string; externalId: string | null } | null>(null);
  const [error, setError] = useState<string | null>(null);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const detected = detectPlatform(url.trim());
    if (!detected) {
      setError('No reconocemos ese link. Probá con una URL de YouTube, Vimeo o Twitch.');
      return;
    }
    setError(null);
    setConfirmed({ url: url.trim(), platform: detected.platform, externalId: detected.externalId });
  }

  if (confirmed) {
    return (
      <ClipEditor
        sourceUrl={confirmed.url}
        sourcePlatform={confirmed.platform as 'YOUTUBE' | 'VIMEO' | 'TWITCH'}
        sourceExternalId={confirmed.externalId}
        onCancel={() => setConfirmed(null)}
      />
    );
  }

  return (
    <div className="upload-page">
      <h1>Importar desde un link</h1>
      <form className="upload-form" onSubmit={handleSubmit}>
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
        />
        <button type="submit">Continuar</button>
        <p className="import-hint">Soportamos YouTube, Vimeo y Twitch.</p>
      </form>
    </div>
  );
}
