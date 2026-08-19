package com.clipshare.worker;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipQueuePublisher;
import com.clipshare.clip.ClipService;
import com.clipshare.storage.FileHasher;
import com.clipshare.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumidor de la cola de Redis (docs/SPEC.md sección 4): corre solo en el contenedor
 * `worker` (perfil "worker", el único con ffmpeg instalado). Usa un hilo daemon propio en
 * vez de @Scheduled porque queremos un BRPOP bloqueante de baja latencia, no polling.
 *
 * Cada paso de escritura a la base pasa por {@link ClipService} (bean distinto) para que
 * @Transactional funcione de verdad — auto-invocación dentro de la misma clase no pasa por
 * el proxy de Spring. El trabajo de ffmpeg en sí corre fuera de cualquier transacción:
 * puede tardar bastante y no tiene sentido mantener una conexión a la base abierta ese rato.
 */
@Component
@Profile("worker")
public class ClipProcessingWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClipProcessingWorker.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_DURATION_MS = 20_000;

    private final StringRedisTemplate redisTemplate;
    private final ClipService clipService;
    private final StorageService storageService;
    private final FfmpegProcessor ffmpegProcessor;
    private final FileHasher fileHasher;

    private volatile boolean running = false;
    private Thread thread;

    public ClipProcessingWorker(StringRedisTemplate redisTemplate, ClipService clipService,
                                 StorageService storageService, FfmpegProcessor ffmpegProcessor, FileHasher fileHasher) {
        this.redisTemplate = redisTemplate;
        this.clipService = clipService;
        this.storageService = storageService;
        this.ffmpegProcessor = ffmpegProcessor;
        this.fileHasher = fileHasher;
    }

    @Override
    public void start() {
        running = true;
        // No-daemon a propósito: sin servidor web (spring.main.web-application-type: none
        // en el perfil "worker"), este es el único hilo que le da al proceso una razón para
        // seguir vivo — si fuera daemon, la JVM se cerraría sola apenas terminara el arranque.
        thread = new Thread(this::loop, "clip-processing-worker");
        thread.start();
        log.info("ClipProcessingWorker iniciado, escuchando '{}'", ClipQueuePublisher.QUEUE_KEY);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            String clipId;
            try {
                clipId = redisTemplate.opsForList().rightPop(ClipQueuePublisher.QUEUE_KEY, POLL_TIMEOUT);
            } catch (Exception e) {
                log.error("Error leyendo la cola de Redis, reintento en 5s", e);
                sleepQuietly(Duration.ofSeconds(5));
                continue;
            }
            if (clipId == null) {
                continue; // timeout normal del RPOP bloqueante: seguir escuchando
            }

            UUID id = UUID.fromString(clipId);
            try {
                processClip(id);
            } catch (Exception e) {
                log.error("Fallo procesando clip {}", id, e);
                clipService.markFailed(id);
            }
        }
    }

    private void processClip(UUID clipId) throws IOException {
        String rawRelativePath = clipService.getRawFilePath(clipId);
        clipService.markProcessing(clipId);

        Path rawPath = storageService.resolveLocalPath(rawRelativePath);
        Path workVideo = storageService.resolveLocalPath("work/" + clipId + "/final.mp4");
        Path workThumb = storageService.resolveLocalPath("work/" + clipId + "/thumb.jpg");

        FfmpegProcessor.ProcessResult result = ffmpegProcessor.process(rawPath, workVideo, workThumb, MAX_DURATION_MS);
        String contentHash = fileHasher.sha256Hex(workVideo);

        Optional<Clip> duplicate = clipService.findByContentHash(contentHash)
                .filter(existing -> !existing.getId().equals(clipId));
        if (duplicate.isPresent()) {
            log.warn("Clip {} es un duplicado exacto de {} (content_hash={}), se rechaza",
                    clipId, duplicate.get().getId(), contentHash);
            clipService.markRejectedDuplicate(clipId);
            deleteQuietly(workVideo);
            deleteQuietly(workThumb);
            storageService.delete(rawRelativePath);
            return;
        }

        // Solo se copia a "public/" (servido por /media/clips/**, ver WebConfig) una vez que
        // pasó la "moderación" — mock que aprueba todo en esta fase, ver ClipService.markPublished.
        Path publicVideo = storageService.resolveLocalPath("public/" + clipId + "/final.mp4");
        Path publicThumb = storageService.resolveLocalPath("public/" + clipId + "/thumb.jpg");
        Files.createDirectories(publicVideo.getParent());
        Files.move(workVideo, publicVideo, StandardCopyOption.REPLACE_EXISTING);
        Files.move(workThumb, publicThumb, StandardCopyOption.REPLACE_EXISTING);

        clipService.markPublished(clipId, result.durationMs(), result.width(), result.height(), contentHash,
                "public/" + clipId + "/final.mp4", "public/" + clipId + "/thumb.jpg");

        storageService.delete(rawRelativePath);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("No se pudo borrar {}", path, e);
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
