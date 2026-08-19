package com.clipshare.clip;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Encola el procesamiento de un clip (ffmpeg + normalización) para que lo levante el
 * worker, desacoplado de la API vía Redis (ver docs/SPEC.md sección 4). Cola simple:
 * una lista de Redis usada como FIFO (LPUSH acá, RPOP en {@link com.clipshare.worker.ClipProcessingWorker}).
 */
@Component
public class ClipQueuePublisher {

    public static final String QUEUE_KEY = "queue:clip-processing";

    private final StringRedisTemplate redisTemplate;

    public ClipQueuePublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void enqueue(UUID clipId) {
        redisTemplate.opsForList().leftPush(QUEUE_KEY, clipId.toString());
    }
}
