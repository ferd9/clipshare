import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { CommentSection } from '../comments/CommentSection';
import type { ClipDetail } from './types';
import { mediaUrl } from './clipsApi';
import { SUPPORTED_PLATFORMS } from './platformDetection';

function formatDuration(ms: number | null): string {
  if (!ms) return '';
  return `${Math.round(ms / 1000)}s`;
}

// Se arma solo a partir de SUPPORTED_PLATFORMS (única fuente, ver platformDetection.ts) — una
// plataforma nueva ahí queda etiquetada acá sin tocar nada más.
const PLATFORM_LABEL: Record<string, string> = Object.fromEntries(
  SUPPORTED_PLATFORMS.map((p) => [p.value, p.label]),
);

interface ClipCardProps {
  clip: ClipDetail;
  /** Compartido entre todos los clips del feed (no por clip) — igual que TikTok/Shorts: una
   * vez que el usuario activa el sonido, el resto de los clips que vengan también arrancan
   * con sonido, no vuelven a silenciarse solos. */
  muted: boolean;
  onToggleMuted: () => void;
  /** Avisa al feed que ESTE clip pasó a ser el que más se ve en pantalla — ver ClipFeed, lo
   * usa para saber cuándo pedir la próxima página (scroll infinito). */
  onActive: () => void;
}

/**
 * Una "diapositiva" del feed estilo TikTok/Shorts (docs/SPEC.md): ocupa toda la altura
 * disponible (ver .clip-feed-slide en clips.css), con el video de fondo y los controles
 * superpuestos — a diferencia de la vieja grilla, folder con comentarios siempre visibles,
 * acá los comentarios viven en un panel deslizable que se abre con el ícono 💬 (ver
 * CommentSection alwaysOpen).
 */
export function ClipCard({ clip, muted, onToggleMuted, onActive }: ClipCardProps) {
  const platformLabel = clip.sourceType === 'EXTERNAL_CAPTURE' ? PLATFORM_LABEL[clip.sourcePlatform] : null;
  const videoRef = useRef<HTMLVideoElement>(null);
  const slideRef = useRef<HTMLDivElement>(null);
  const [isActive, setIsActive] = useState(false);
  const [commentsOpen, setCommentsOpen] = useState(false);

  // root: null (viewport del navegador) alcanza acá porque .clip-feed ya ocupa prácticamente
  // toda la altura visible (ver layout.css) — no hace falta pasar el contenedor scrolleable
  // como root para que la detección de "qué tan visible está" sea razonablemente precisa.
  useEffect(() => {
    const el = slideRef.current;
    if (!el) return undefined;
    const observer = new IntersectionObserver(
      ([entry]) => {
        setIsActive(entry.isIntersecting);
        if (entry.isIntersecting) onActive();
      },
      { threshold: 0.6 },
    );
    observer.observe(el);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reproduce solo mientras es el clip activo (en pantalla) — se pausa y vuelve al principio
  // al salir de vista, así la próxima vez que aparezca arranca desde el inicio (como
  // TikTok/Shorts), en vez de seguir corriendo de fondo o retomar a mitad de camino.
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    if (isActive) {
      video.currentTime = 0;
      video.play().catch(() => {
        // Autoplay con sonido bloqueado por el navegador — no es un error real, arranca
        // muted por default (ver ClipFeed) así que esto no debería pasar en la práctica.
      });
    } else {
      video.pause();
    }
  }, [isActive]);

  return (
    <article className="clip-feed-slide" ref={slideRef}>
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- clip de usuario, sin pista de subtítulos */}
      <video
        ref={videoRef}
        className="clip-feed-video"
        src={mediaUrl(clip.videoUrl)}
        poster={mediaUrl(clip.thumbnailUrl)}
        loop
        muted={muted}
        playsInline
        onClick={onToggleMuted}
      />

      <div className="clip-feed-overlay">
        {clip.title && <p className="clip-feed-title">{clip.title}</p>}
        <div className="clip-feed-meta">
          <span>{clip.ownerDisplayName}</span>
          {formatDuration(clip.durationMs) && <span>{formatDuration(clip.durationMs)}</span>}
          {platformLabel && <span>vía {platformLabel}</span>}
        </div>
      </div>

      <div className="clip-feed-actions">
        <button
          type="button"
          className="clip-feed-action-button"
          onClick={onToggleMuted}
          aria-label={muted ? 'Activar sonido' : 'Silenciar'}
          title={muted ? 'Activar sonido' : 'Silenciar'}
        >
          {muted ? '🔇' : '🔊'}
        </button>
        <button
          type="button"
          className="clip-feed-action-button"
          onClick={() => setCommentsOpen(true)}
          aria-label="Ver comentarios"
          title="Comentarios"
        >
          💬
        </button>
        <Link to={`/report/${clip.id}`} className="clip-feed-action-button" aria-label="Reportar" title="Reportar">
          🚩
        </Link>
      </div>

      {commentsOpen && (
        <div className="clip-feed-comments-overlay" onClick={() => setCommentsOpen(false)}>
          <div className="clip-feed-comments-panel" onClick={(event) => event.stopPropagation()}>
            <div className="clip-feed-comments-header">
              <h3>Comentarios</h3>
              <button type="button" onClick={() => setCommentsOpen(false)} aria-label="Cerrar">
                ×
              </button>
            </div>
            <CommentSection clipId={clip.id} alwaysOpen />
          </div>
        </div>
      )}
    </article>
  );
}
