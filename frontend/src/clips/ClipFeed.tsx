import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { ClipCard } from './ClipCard';
import { getFeed } from './clipsApi';
import type { ClipDetail } from './types';
import './clips.css';

export function ClipFeed() {
  const [clips, setClips] = useState<ClipDetail[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getFeed()
      .then((result) => {
        if (!cancelled) setClips(result.items);
      })
      .catch((err) => {
        if (!cancelled) setError(extractErrorMessage(err, 'No se pudo cargar el feed'));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) return <p className="clips-error">{error}</p>;
  if (clips === null) return <p className="clips-loading">Cargando…</p>;

  if (clips.length === 0) {
    return (
      <div className="clips-empty">
        <p>Todavía no hay clips publicados.</p>
        <Link to="/upload">Subí el primero</Link>
      </div>
    );
  }

  return (
    <div className="clip-feed">
      {clips.map((clip) => (
        <ClipCard key={clip.id} clip={clip} />
      ))}
    </div>
  );
}
