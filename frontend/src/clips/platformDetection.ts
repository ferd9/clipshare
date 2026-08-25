import type { ClipPlatform } from './types';

export interface DetectedSource {
  platform: ClipPlatform;
  externalId: string | null;
}

export interface PlatformConfig {
  value: Exclude<ClipPlatform, 'NONE'>;
  label: string;
  hostnames: string[];
}

/**
 * Único lugar de donde salen la detección, los mensajes de NewClipPage y las etiquetas de
 * ClipCard — yt-dlp en sí soporta muchas más plataformas (ver supportedsites.md en la raíz del
 * repo), pero acá nos limitamos a las 10 más populares para no abrumar la UI. Para sumar una
 * plataforma nueva: un valor más en ClipPlatform (acá y en el enum de ClipPlatform.java, con su
 * migración ALTER TYPE) + una entrada más en esta lista — el resto (detección de hostname,
 * texto de ayuda/error, etiqueta en el feed) se arma solo a partir de esto, sin tocar nada más.
 */
export const SUPPORTED_PLATFORMS: PlatformConfig[] = [
  { value: 'YOUTUBE', label: 'YouTube', hostnames: ['youtube.com', 'youtu.be'] },
  { value: 'TIKTOK', label: 'TikTok', hostnames: ['tiktok.com'] },
  { value: 'INSTAGRAM', label: 'Instagram', hostnames: ['instagram.com'] },
  { value: 'FACEBOOK', label: 'Facebook', hostnames: ['facebook.com', 'fb.watch'] },
  { value: 'TWITTER', label: 'Twitter/X', hostnames: ['twitter.com', 'x.com'] },
  { value: 'TWITCH', label: 'Twitch', hostnames: ['twitch.tv', 'clips.twitch.tv'] },
  { value: 'VIMEO', label: 'Vimeo', hostnames: ['vimeo.com'] },
  { value: 'REDDIT', label: 'Reddit', hostnames: ['reddit.com'] },
  { value: 'DAILYMOTION', label: 'Dailymotion', hostnames: ['dailymotion.com', 'dai.ly'] },
  { value: 'SOUNDCLOUD', label: 'SoundCloud', hostnames: ['soundcloud.com'] },
];

/**
 * Detección liviana solo para nuestra propia metadata (source_platform, ver docs/SPEC.md
 * sección 7) — la descarga real la hace yt-dlp server-side (ClipService.importFromLink), que
 * ya valida por su cuenta si la URL es de verdad un video válido; acá alcanza con reconocer el
 * dominio para no dejar pasar un link de una plataforma que no ofrecemos en el selector.
 */
export function detectPlatform(rawUrl: string): DetectedSource | null {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    return null;
  }
  const host = url.hostname.replace(/^www\./, '').replace(/^m\./, '');
  const match = SUPPORTED_PLATFORMS.find((p) => p.hostnames.includes(host));
  if (!match) return null;

  const externalId = url.pathname.split('/').filter(Boolean).at(-1) ?? null;
  return { platform: match.value, externalId };
}
