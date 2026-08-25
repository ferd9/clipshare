import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { extractErrorMessage } from '../auth/AuthContext';
import { ClipCard } from './ClipCard';
import { getFeed } from './clipsApi';
import type { ClipDetail } from './types';
import './clips.css';

const PAGE_SIZE = 10;
// Cuántos clips le faltan al usuario para llegar al final de lo ya cargado antes de pedir la
// próxima página — pedirla apenas se acerca, no cuando ya llegó, para que el scroll nunca se
// quede esperando (igual que TikTok/Shorts, que precargan antes de que haga falta).
const PREFETCH_REMAINING = 3;

/**
 * Feed público estilo TikTok/Shorts (reemplaza la vieja grilla, docs/SPEC.md): un clip ocupa
 * toda la altura disponible (ver .clip-feed en clips.css, con scroll-snap vertical), se pasa
 * al siguiente con scroll/swipe. El "clip activo" (el que más se ve en pantalla, ver
 * ClipCard/IntersectionObserver) es el único que reproduce — y también dispara la carga de
 * la próxima página cuando el usuario se acerca al final de lo ya traído.
 */
export function ClipFeed() {
  const [clips, setClips] = useState<ClipDetail[] | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  // Silenciado por default (autoplay con sonido lo bloquea el navegador) — compartido entre
  // TODOS los clips, no por clip: una vez que el usuario activa el sonido, se mantiene activo
  // al pasar al siguiente, en vez de volver a silenciarse solo cada vez.
  const [muted, setMuted] = useState(true);

  // Ref (no state) para el guard de "ya hay un pedido de más clips en curso" — evita que el
  // efecto de abajo dispare pedidos duplicados mientras el primero todavía no resolvió.
  const loadingMoreRef = useRef(false);
  // Contenedor con el scroll-snap (ver .clip-feed en clips.css) — necesario para poder
  // desplazarlo a mano desde las flechas del teclado y los botones ⌃/⌄.
  const feedRef = useRef<HTMLDivElement>(null);

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

  const loadMore = useCallback(
    async (currentPage: number) => {
      loadingMoreRef.current = true;
      try {
        const result = await getFeed(currentPage + 1, PAGE_SIZE);
        setClips((current) => [...(current ?? []), ...result.items]);
        setPage(result.page);
        setTotalPages(result.totalPages);
      } catch (err) {
        setError(extractErrorMessage(err, 'No se pudieron cargar más clips'));
      } finally {
        loadingMoreRef.current = false;
      }
    },
    [],
  );

  // Dispara la precarga en cuanto el clip activo entra en la zona de "casi al final" — sin
  // esperar a un botón, sin esperar a llegar literalmente al último.
  useEffect(() => {
    if (!clips) return;
    const remaining = clips.length - 1 - activeIndex;
    if (remaining <= PREFETCH_REMAINING && page + 1 < totalPages && !loadingMoreRef.current) {
      void loadMore(page);
    }
  }, [activeIndex, clips, page, totalPages, loadMore]);

  // Todos los clips miden exactamente el alto del contenedor (ver .clip-feed-slide, height:
  // 100%) — así que "ir al clip N" es simplemente desplazarse a N veces esa altura, sin
  // necesitar una ref por cada clip.
  const scrollToIndex = useCallback(
    (index: number) => {
      const container = feedRef.current;
      if (!container || !clips) return;
      const clamped = Math.max(0, Math.min(index, clips.length - 1));
      container.scrollTo({ top: clamped * container.clientHeight, behavior: 'smooth' });
    },
    [clips],
  );

  // Flechas arriba/abajo para pasar de clip sin usar el mouse/touch — igual que el scroll,
  // pero accesible desde el teclado. Se ignora mientras el foco está en un campo de texto
  // (ej. escribiendo un comentario) para no robarle las flechas a esa interacción.
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return;
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        scrollToIndex(activeIndex + 1);
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        scrollToIndex(activeIndex - 1);
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [activeIndex, scrollToIndex]);

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
    <>
      <div className="clip-feed" ref={feedRef}>
        {clips.map((clip, index) => (
          <ClipCard
            key={clip.id}
            clip={clip}
            muted={muted}
            onToggleMuted={() => setMuted((m) => !m)}
            onActive={() => setActiveIndex(index)}
          />
        ))}
        {error && <p className="clip-feed-error">{error}</p>}
      </div>

      {/* Mismo desplazamiento que las flechas del teclado, para quien prefiera clickear —
       * fuera de .clip-feed a propósito (position: fixed, no se mueve con el scroll interno). */}
      <div className="clip-feed-nav-buttons">
        <button
          type="button"
          className="clip-feed-nav-button"
          onClick={() => scrollToIndex(activeIndex - 1)}
          disabled={activeIndex === 0}
          aria-label="Clip anterior"
          title="Clip anterior"
        >
          ▲
        </button>
        <button
          type="button"
          className="clip-feed-nav-button"
          onClick={() => scrollToIndex(activeIndex + 1)}
          disabled={activeIndex === clips.length - 1}
          aria-label="Siguiente clip"
          title="Siguiente clip"
        >
          ▼
        </button>
      </div>
    </>
  );
}
