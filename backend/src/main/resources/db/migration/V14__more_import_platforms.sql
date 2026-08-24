-- Amplía las plataformas soportadas para importar por link (antes solo YouTube/Vimeo/Twitch,
-- ver V2__clips_media.sql) a las 10 más populares de las que soporta yt-dlp — ver
-- ClipService.importFromLink y frontend/src/clips/platformDetection.ts (SUPPORTED_PLATFORMS,
-- la lista única de la que sale la detección, los mensajes y este enum). Pensado para poder
-- seguir ampliándose: una plataforma nueva es un ALTER TYPE más acá, un valor más en
-- ClipPlatform.java, y una entrada más en SUPPORTED_PLATFORMS.
ALTER TYPE clip_platform ADD VALUE 'TIKTOK';
ALTER TYPE clip_platform ADD VALUE 'INSTAGRAM';
ALTER TYPE clip_platform ADD VALUE 'FACEBOOK';
ALTER TYPE clip_platform ADD VALUE 'TWITTER';
ALTER TYPE clip_platform ADD VALUE 'REDDIT';
ALTER TYPE clip_platform ADD VALUE 'DAILYMOTION';
ALTER TYPE clip_platform ADD VALUE 'SOUNDCLOUD';
