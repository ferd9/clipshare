-- Recorte preciso de una captura externa (docs/SPEC.md sección 9, Caso B): el usuario puede
-- ajustar, después de grabar, qué sub-rango de SU PROPIA grabación (nunca del video fuente)
-- quiere publicar, con una UI tipo filmstrip en el frontend. Estos offsets son relativos al
-- archivo subido (0 = arranque de la grabación), no al video de origen — eso lo siguen
-- cubriendo source_clip_start_ms/source_clip_end_ms, ya existentes desde V2.
-- NULL en ambas columnas (el caso por defecto, y siempre para OWN_UPLOAD) preserva el
-- comportamiento anterior: el worker usa el archivo completo, recortado desde el arranque
-- hasta un máximo de 20s.
ALTER TABLE clips ADD COLUMN trim_start_ms INTEGER;
ALTER TABLE clips ADD COLUMN trim_end_ms INTEGER;
