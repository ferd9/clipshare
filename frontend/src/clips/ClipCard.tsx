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

export function ClipCard({ clip }: { clip: ClipDetail }) {
  const platformLabel = clip.sourceType === 'EXTERNAL_CAPTURE' ? PLATFORM_LABEL[clip.sourcePlatform] : null;

  return (
    <article className="clip-card">
      <video
        className="clip-card-video"
        src={mediaUrl(clip.videoUrl)}
        poster={mediaUrl(clip.thumbnailUrl)}
        controls
        preload="metadata"
        playsInline
      />
      {clip.title && <h3 className="clip-card-title">{clip.title}</h3>}
      <div className="clip-card-meta">
        <span>{clip.ownerDisplayName}</span>
        <span>{formatDuration(clip.durationMs)}</span>
      </div>
      <div className="clip-card-footer">
        {platformLabel && <span className="clip-card-source">vía {platformLabel}</span>}
        <Link to={`/report/${clip.id}`} className="clip-card-report">
          Reportar
        </Link>
      </div>
      <CommentSection clipId={clip.id} />
    </article>
  );
}
