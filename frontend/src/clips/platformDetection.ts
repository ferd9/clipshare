import type { ClipPlatform } from './types';

export interface DetectedSource {
  platform: ClipPlatform;
  externalId: string | null;
}

/**
 * Detección liviana solo para nuestra propia metadata (source_platform/source_external_id,
 * ver docs/SPEC.md sección 7) — react-player hace su propio parseo, más robusto, para
 * decidir qué reproductor usar. Acotado a las tres plataformas del stack (sección 3).
 */
export function detectPlatform(rawUrl: string): DetectedSource | null {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    return null;
  }
  const host = url.hostname.replace(/^www\./, '').replace(/^m\./, '');

  if (host === 'youtube.com' || host === 'youtu.be') {
    const id = host === 'youtu.be' ? url.pathname.slice(1) : url.searchParams.get('v');
    return id ? { platform: 'YOUTUBE', externalId: id } : null;
  }

  if (host === 'vimeo.com') {
    const id = url.pathname.split('/').filter(Boolean)[0] ?? null;
    return id && /^\d+$/.test(id) ? { platform: 'VIMEO', externalId: id } : null;
  }

  if (host === 'twitch.tv' || host === 'clips.twitch.tv') {
    const segments = url.pathname.split('/').filter(Boolean);
    const id = segments.at(-1) ?? null;
    return id ? { platform: 'TWITCH', externalId: id } : null;
  }

  return null;
}
