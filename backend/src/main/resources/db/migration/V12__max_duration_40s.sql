-- El máximo de duración del clip final pasa de 20s a 40s (pedido de producto) — ver
-- ClipService.MAX_CLIP_DURATION_MS, única fuente de verdad para la validación de la app; este
-- CHECK es la red de seguridad a nivel de datos. Mismo margen de tolerancia que V10 (ffmpeg
-- -t durante un re-encode puede pasarse unos ms del valor pedido por redondeo de frame/GOP).
ALTER TABLE clips DROP CONSTRAINT clips_duration_ms_check;
ALTER TABLE clips ADD CONSTRAINT clips_duration_ms_check CHECK (duration_ms <= 41000);
