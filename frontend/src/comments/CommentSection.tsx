import { useEffect, useState } from 'react';
import { extractErrorMessage } from '../auth/AuthContext';
import { getComments } from './commentsApi';
import { CommentForm } from './CommentForm';
import { CommentItem } from './CommentItem';
import type { CommentSummary } from './types';
import './comments.css';

const PAGE_SIZE = 20;

/** Sección de comentarios de un clip, colapsada por defecto (docs/SPEC.md sección 11) — no
 * hay una página de detalle de clip separada en este producto (feed de tarjetas estilo
 * Coub/TikTok), así que vive expandible dentro de la propia tarjeta, ver ClipCard.tsx.
 * Nota: las respuestas (parent_comment_id) no se muestran anidadas visualmente todavía —
 * aparecen en la lista plana ordenadas por fecha como cualquier otro comentario nuevo. */
interface CommentSectionProps {
  clipId: string;
  /** true cuando este componente vive dentro de un panel que el propio caller ya abrió/cerró
   * (ver ClipCard, feed estilo TikTok con panel deslizable) — oculta el botón interno de
   * "Comentarios"/"Ocultar comentarios" (sería un segundo toggle redundante) y carga los
   * comentarios directo al montar, en vez de esperar un click. */
  alwaysOpen?: boolean;
}

export function CommentSection({ clipId, alwaysOpen = false }: CommentSectionProps) {
  const [open, setOpen] = useState(alwaysOpen);
  const [comments, setComments] = useState<CommentSummary[] | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      const result = await getComments(clipId, 0, PAGE_SIZE);
      setComments(result.items);
      setPage(result.page);
      setTotalPages(result.totalPages);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudieron cargar los comentarios'));
    }
  }

  // Equivalente a lo que dispara handleToggle en el modo normal, pero sin esperar un click —
  // alwaysOpen implica que ya "está abierto" desde que este componente se monta.
  useEffect(() => {
    if (alwaysOpen) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [alwaysOpen, clipId]);

  function handleToggle() {
    setOpen((current) => {
      const next = !current;
      if (next && comments === null) void load();
      return next;
    });
  }

  async function handleLoadMore() {
    setLoadingMore(true);
    setError(null);
    try {
      const result = await getComments(clipId, page + 1, PAGE_SIZE);
      setComments((current) => [...(current ?? []), ...result.items]);
      setPage(result.page);
      setTotalPages(result.totalPages);
    } catch (err) {
      setError(extractErrorMessage(err, 'No se pudieron cargar más comentarios'));
    } finally {
      setLoadingMore(false);
    }
  }

  function handleCreated(comment: CommentSummary) {
    setComments((current) => (current ? [comment, ...current] : [comment]));
  }

  function handleDeleted(commentId: string) {
    setComments((current) => current?.filter((c) => c.id !== commentId) ?? null);
  }

  return (
    <div className="comment-section">
      {!alwaysOpen && (
        <button type="button" className="comment-toggle" onClick={handleToggle}>
          {open ? 'Ocultar comentarios' : 'Comentarios'}
        </button>
      )}

      {open && (
        <div className="comment-section-body">
          <CommentForm clipId={clipId} onCreated={handleCreated} />

          {error && (
            <p className="clips-error" role="alert">
              {error}
            </p>
          )}
          {comments === null && !error && <p className="clips-loading">Cargando…</p>}
          {comments !== null && comments.length === 0 && (
            <p className="comment-empty">Sé el primero en comentar.</p>
          )}

          {comments && comments.length > 0 && (
            <ul className="comment-list">
              {comments.map((comment) => (
                <CommentItem
                  key={comment.id}
                  clipId={clipId}
                  comment={comment}
                  onReply={handleCreated}
                  onDeleted={handleDeleted}
                />
              ))}
            </ul>
          )}

          {comments && page + 1 < totalPages && (
            <button type="button" className="comment-load-more" onClick={() => void handleLoadMore()} disabled={loadingMore}>
              {loadingMore ? 'Cargando…' : 'Cargar más comentarios'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
