import ReactPlayer from 'react-player';
import { ExternalLinkGuard } from './ExternalLinkGuard';
import type { AttachmentSummary } from './types';

const REACT_PLAYER_PLATFORMS = new Set(['YOUTUBE', 'VIMEO', 'TWITCH']);

const PLATFORM_LABEL: Record<string, string> = {
  YOUTUBE: 'YouTube',
  VIMEO: 'Vimeo',
  TWITCH: 'Twitch',
  TIKTOK: 'TikTok',
  FACEBOOK: 'Facebook',
  INSTAGRAM: 'Instagram',
};

/**
 * Renderizado de un adjunto LINK (docs/SPEC.md sección 11.10): si es embebible, el video se
 * reproduce ahí mismo — YouTube/Vimeo/Twitch vía `react-player` (ya integrado para el flujo
 * de creación de clips), TikTok con un iframe armado a mano a partir del id (NUNCA
 * `dangerouslySetInnerHTML` con el HTML crudo que devolvería un oEmbed de terceros — es
 * contenido no confiable, vector de XSS). Si no es embebible (dominio no reconocido, o
 * Facebook/Instagram todavía sin credenciales configuradas), se muestra una tarjeta de link
 * simple. El interstitial de ExternalLinkGuard aplica a la navegación fuera del sitio, no a
 * mirar el video embebido sin salir de acá — por eso el player en sí no pasa por el guard,
 * solo el botón "Ver en {plataforma} original ↗".
 */
export function CommentLinkPreview({ attachment, onReport }: { attachment: AttachmentSummary; onReport: () => void }) {
  if (!attachment.linkUrl) return null;

  const platformLabel = attachment.embedPlatform ? PLATFORM_LABEL[attachment.embedPlatform] : null;

  if (attachment.embeddable && attachment.embedPlatform && REACT_PLAYER_PLATFORMS.has(attachment.embedPlatform)) {
    return (
      <div className="comment-embed">
        <div className="comment-embed-player">
          <ReactPlayer src={attachment.linkUrl} controls width="100%" height="100%" />
        </div>
        <ExternalLinkGuard url={attachment.linkUrl} onReport={onReport}>
          Ver en {platformLabel} original ↗
        </ExternalLinkGuard>
      </div>
    );
  }

  if (attachment.embeddable && attachment.embedPlatform === 'TIKTOK' && attachment.embedExternalId) {
    return (
      <div className="comment-embed">
        <div className="comment-embed-player comment-embed-player-tiktok">
          <iframe
            src={`https://www.tiktok.com/embed/v2/${attachment.embedExternalId}`}
            title="TikTok"
            sandbox="allow-scripts allow-same-origin allow-presentation"
            allow="encrypted-media"
          />
        </div>
        <ExternalLinkGuard url={attachment.linkUrl} onReport={onReport}>
          Ver en TikTok original ↗
        </ExternalLinkGuard>
      </div>
    );
  }

  // No embebible: tarjeta de link simple, con el interstitial en el botón de navegación
  // (docs/SPEC.md sección 11.9) — mismo tratamiento que cualquier otro enlace no reconocido.
  return (
    <div className="comment-link-card">
      {attachment.embedThumbnailUrl && (
        <img src={attachment.embedThumbnailUrl} alt="" className="comment-link-card-thumb" />
      )}
      <div className="comment-link-card-body">
        {attachment.embedTitle && <p className="comment-link-card-title">{attachment.embedTitle}</p>}
        <ExternalLinkGuard url={attachment.linkUrl} onReport={onReport}>
          Ver en {platformLabel ?? attachment.linkDomain} ↗
        </ExternalLinkGuard>
      </div>
    </div>
  );
}
