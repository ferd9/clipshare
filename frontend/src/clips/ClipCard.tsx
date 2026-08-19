import { Link } from 'react-router-dom';
import type { ClipDetail } from './types';
import { mediaUrl } from './clipsApi';

function formatDuration(ms: number | null): string {
  if (!ms) return '';
  return `${Math.round(ms / 1000)}s`;
}

const PLATFORM_LABEL: Record<string, string> = {
  YOUTUBE: 'YouTube',
  VIMEO: 'Vimeo',
  TWITCH: 'Twitch',
};

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
    </article>
  );
}
