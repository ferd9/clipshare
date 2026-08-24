package com.clipshare.clip;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Encola trabajo para el worker vía Redis (docs/SPEC.md sección 4): una lista usada como
 * FIFO (LPUSH acá, RPOP en {@link com.clipshare.worker.ClipProcessingWorker}). El mensaje es
 * JSON, no un UUID plano como antes — desde el pipeline de import server-side hay dos fases
 * bien distintas (STAGE: descargar/normalizar; FINALIZE: recortar+mux de audio+moderar), y
 * el worker necesita saber cuál de las dos le toca correr para un clip dado sin tener que
 * inferirlo de processing_status (que sigue reflejando el estado general, no la fase de cola).
 */
@Component
public class ClipQueuePublisher {

    public static final String QUEUE_KEY = "queue:clip-processing";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ClipQueuePublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public record ClipJob(UUID clipId, ClipJobType jobType) {
    }

    public void enqueueStage(UUID clipId) {
        enqueue(new ClipJob(clipId, ClipJobType.STAGE));
    }

    public void enqueueFinalize(UUID clipId) {
        enqueue(new ClipJob(clipId, ClipJobType.FINALIZE));
    }

    private void enqueue(ClipJob job) {
        // Jackson 3: writeValueAsString tira JacksonException (unchecked), no hace falta try/catch.
        redisTemplate.opsForList().leftPush(QUEUE_KEY, objectMapper.writeValueAsString(job));
    }
}
