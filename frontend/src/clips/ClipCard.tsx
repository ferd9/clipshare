import type { ClipDetail } from './types';
import { mediaUrl } from './clipsApi';

function formatDuration(ms: number | null): string {
  if (!ms) return '';
  return `${Math.round(ms / 1000)}s`;
}

export function ClipCard({ clip }: { clip: ClipDetail }) {
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
    </article>
  );
}
