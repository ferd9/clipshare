import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { ClipCard } from './ClipCard';
import { getFeed } from './clipsApi';
import type { ClipDetail } from './types';
import './clips.css';

const PAGE_SIZE = 20;

export function ClipFeed() {
  const [clips, setClips] = useState<ClipDetail[] | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getFeed(0, PAGE_SIZE)
      .then((result) => {
        if (cancelled) return;
        setClips(result.items);
        setPage(result.page);
        setTotalPages(result.totalPages);
      })
      .catch((err) => {
        if (!cancelled) setError(extractErrorMessage(err, 'No se pudo cargar el feed'));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleLoadMore() {
    setLoadingMore(true);
    setError(null);
    try {
      const result = await getFeed(page + 1, PAGE_SIZE);
      setClips((current) => [...(current ?? []), ...result.items]);
      setPage(result.page);
      setTotalPages(result.totalPages);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudieron cargar más clips'));
    } finally {
      setLoadingMore(false);
    }
  }

  if (error && clips === null) return <p className="clips-error">{error}</p>;
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
    <div>
      <div className="clip-feed">
        {clips.map((clip) => (
          <ClipCard key={clip.id} clip={clip} />
        ))}
      </div>
      {error && <p className="clips-error">{error}</p>}
      {page + 1 < totalPages && (
        <div className="clip-feed-more">
          <button type="button" onClick={() => void handleLoadMore()} disabled={loadingMore}>
            {loadingMore ? 'Cargando…' : 'Cargar más'}
          </button>
        </div>
      )}
    </div>
  );
}
