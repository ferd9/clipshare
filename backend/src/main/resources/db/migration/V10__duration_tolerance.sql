-- ffmpeg -t durante un re-encode no corta al milisegundo exacto: el redondeo a límite de
-- frame/GOP del codec de origen puede pasarse unos milisegundos del valor pedido (visto en
-- producción: se pidieron 20000ms y el archivo final salió en 20020ms). El límite de 20s de
-- docs/SPEC.md sección 7 es un tope de producto, no una garantía de precisión de ffmpeg — sin
-- este margen, cualquier recorte que pida el máximo exacto (20.0s) puede terminar rechazado
-- por este CHECK ya en la transacción final de ClipProcessingWorker.finalizeClip, con el clip
-- quedando FAILED pese a que el video se generó bien.
ALTER TABLE clips DROP CONSTRAINT clips_duration_ms_check;
ALTER TABLE clips ADD CONSTRAINT clips_duration_ms_check CHECK (duration_ms <= 21000);
