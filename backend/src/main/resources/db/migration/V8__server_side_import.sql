-- Rediseño del import externo: en vez de grabar la pantalla (getDisplayMedia, calidad
-- inaceptable en la práctica — confirmado probando en vivo), el backend ahora descarga el
-- video real server-side con yt-dlp. Decisión de producto tomada a propósito por el usuario,
-- sabiendo que pisa los ToS de YouTube/Vimeo/Twitch (docs/SPEC.md sección 2 quedó superada
-- para este flujo específico). El pipeline de moderación/DMCA/strikes sigue exactamente
-- igual — de hecho importa más ahora, porque circula contenido de terceros real.

-- Nuevo estado intermedio: la fuente (descargada o subida) ya está normalizada y lista para
-- que el usuario elija el recorte final + audio, antes de la codificación definitiva.
ALTER TYPE processing_status ADD VALUE 'AWAITING_EDIT';
