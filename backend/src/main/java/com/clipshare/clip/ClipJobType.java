package com.clipshare.clip;

/**
 * Los dos tipos de trabajo que puede pedirle la API al worker vía la cola de Redis (ver
 * {@link ClipQueuePublisher} y {@code com.clipshare.worker.ClipProcessingWorker}). No se
 * modela como parte de {@link ProcessingStatus} (que sigue reflejando el estado general del
 * clip) para no tener que agregar más valores al enum nativo de Postgres — eso exige una
 * migración propia por cada valor nuevo (ver V8__server_side_import.sql).
 */
public enum ClipJobType {
    /** Descargar (si es EXTERNAL_CAPTURE) y normalizar la fuente completa a un archivo
     * "editable" que el frontend pueda reproducir para elegir el recorte final. */
    STAGE,
    /** Recortar el archivo "editable" al rango elegido, aplicar mute/reemplazo de audio,
     * correr moderación y publicar. */
    FINALIZE
}
