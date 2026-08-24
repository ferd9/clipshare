package com.clipshare.clip;

/**
 * Plataformas soportadas para importar por link (docs/SPEC.md sección 7) — yt-dlp en sí
 * soporta muchísimas más (ver supportedsites.md), pero acá nos limitamos a las 10 más
 * populares para no abrumar la UI. Lista pensada para poder seguir ampliándose: agregar una
 * plataforma nueva es sumar un valor acá + su migración (ALTER TYPE clip_platform ADD VALUE,
 * ver V14__more_import_platforms.sql) + una entrada en SUPPORTED_PLATFORMS
 * (frontend/src/clips/platformDetection.ts, la fuente única de la detección y los textos).
 */
public enum ClipPlatform {
    YOUTUBE, TIKTOK, INSTAGRAM, FACEBOOK, TWITTER, TWITCH, VIMEO, REDDIT, DAILYMOTION, SOUNDCLOUD, NONE
}
