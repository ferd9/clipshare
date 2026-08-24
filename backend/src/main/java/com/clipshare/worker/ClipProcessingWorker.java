package com.clipshare.worker;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipJobType;
import com.clipshare.clip.ClipQueuePublisher;
import com.clipshare.clip.ClipService;
import com.clipshare.clip.ClipSourceType;
import com.clipshare.moderation.ModerationService;
import com.clipshare.storage.FileHasher;
import com.clipshare.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Consumidor de la cola de Redis (docs/SPEC.md sección 4): corre solo en el contenedor
 * `worker` (perfil "worker", el único con ffmpeg/yt-dlp instalados). Usa un hilo daemon propio
 * en vez de @Scheduled porque queremos un BRPOP bloqueante de baja latencia, no polling.
 *
 * Pipeline en dos fases (ver ClipService): STAGE descarga (si hace falta) y normaliza la
 * fuente completa a un archivo "editable"; FINALIZE recorta ese archivo al rango elegido por
 * el usuario, aplica mute/reemplazo de audio, modera y publica. Mismo código para OWN_UPLOAD
 * y EXTERNAL_CAPTURE desde STAGE en adelante.
 *
 * Cada paso de escritura a la base pasa por {@link ClipService} (bean distinto) para que
 * @Transactional funcione de verdad — auto-invocación dentro de la misma clase no pasa por
 * el proxy de Spring. El trabajo de ffmpeg/yt-dlp en sí corre fuera de cualquier transacción:
 * puede tardar bastante (hasta 10 minutos de descarga) y no tiene sentido mantener una
 * conexión a la base abierta ese rato.
 */
@Component
@Profile("worker")
public class ClipProcessingWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClipProcessingWorker.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_DURATION_MS = ClipService.MAX_CLIP_DURATION_MS;
    // 1/seg sobre TODO el clip, no un tope fijo — antes era un literal 20 atado a mano al
    // viejo máximo de 20s; con el máximo ahora en 40s (ver ClipService.MAX_CLIP_DURATION_MS)
    // un valor fijo habría dejado la segunda mitad de cualquier clip largo sin moderar.
    private static final int MODERATION_MAX_FRAMES = (int) (ClipService.MAX_CLIP_DURATION_MS / 1000);

    private final StringRedisTemplate redisTemplate;
    private final ClipService clipService;
    private final StorageService storageService;
    private final FfmpegProcessor ffmpegProcessor;
    private final YtDlpClient ytDlpClient;
    private final FileHasher fileHasher;
    private final ModerationService moderationService;
    private final ObjectMapper objectMapper;

    private volatile boolean running = false;
    private Thread thread;

    public ClipProcessingWorker(StringRedisTemplate redisTemplate, ClipService clipService,
                                 StorageService storageService, FfmpegProcessor ffmpegProcessor, YtDlpClient ytDlpClient,
                                 FileHasher fileHasher, ModerationService moderationService, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.clipService = clipService;
        this.storageService = storageService;
        this.ffmpegProcessor = ffmpegProcessor;
        this.ytDlpClient = ytDlpClient;
        this.fileHasher = fileHasher;
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
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
            String raw;
            try {
                raw = redisTemplate.opsForList().rightPop(ClipQueuePublisher.QUEUE_KEY, POLL_TIMEOUT);
            } catch (Exception e) {
                log.error("Error leyendo la cola de Redis, reintento en 5s", e);
                sleepQuietly(Duration.ofSeconds(5));
                continue;
            }
            if (raw == null) {
                continue; // timeout normal del RPOP bloqueante: seguir escuchando
            }

            ClipQueuePublisher.ClipJob job;
            try {
                job = objectMapper.readValue(raw, ClipQueuePublisher.ClipJob.class);
            } catch (Exception e) {
                log.error("Mensaje de cola ilegible, se descarta: {}", raw, e);
                continue;
            }

            try {
                if (job.jobType() == ClipJobType.STAGE) {
                    stageClip(job.clipId());
                } else {
                    finalizeClip(job.clipId());
                }
            } catch (Exception e) {
                log.error("Fallo procesando clip {} (job {})", job.clipId(), job.jobType(), e);
                clipService.markFailed(job.clipId(), "Error inesperado procesando el clip");
            }
        }
    }

    // ---- Fase 1: STAGE ----

    private void stageClip(UUID clipId) throws IOException {
        ClipService.StageInput input = clipService.getStageInput(clipId);
        clipService.markProcessing(clipId);

        String rawRelativePath = input.filePath();
        if (input.sourceType() == ClipSourceType.EXTERNAL_CAPTURE && rawRelativePath == null) {
            // Descarga real (hasta 10 minutos de video, puede tardar) — corre acá, nunca en el
            // request HTTP que solo hizo el pre-chequeo rápido de metadata (ClipService.importFromLink).
            rawRelativePath = "raw/" + clipId + "/source.mp4";
            try {
                ytDlpClient.downloadVideo(input.sourceUrl(), storageService.resolveLocalPath(rawRelativePath));
            } catch (YtDlpClient.YtDlpException e) {
                log.warn("No se pudo descargar el clip {}: {}", clipId, e.getMessage());
                clipService.markFailed(clipId, e.getMessage());
                return;
            }
            clipService.markRawDownloaded(clipId, rawRelativePath);
        }

        Path rawPath = storageService.resolveLocalPath(rawRelativePath);
        FfmpegProcessor.ProbeResult sourceProbe;
        try {
            sourceProbe = ffmpegProcessor.probe(rawPath);
        } catch (IOException e) {
            log.warn("No se pudo leer el archivo de video del clip {}", clipId, e);
            clipService.markFailed(clipId, "No se pudo leer el archivo de video");
            storageService.delete(rawRelativePath);
            return;
        }
        if (sourceProbe.durationMs() > ClipService.MAX_SOURCE_DURATION_MS) {
            // Segunda validación real: la de ClipService.importFromLink es solo un pre-chequeo
            // de metadata (puede faltar/mentir) — y la ÚNICA posible para OWN_UPLOAD, que nunca
            // pasa por ese pre-chequeo.
            clipService.markFailed(clipId, "El video no puede superar los 10 minutos");
            storageService.delete(rawRelativePath);
            return;
        }

        Path editableVideo = storageService.resolveLocalPath("work/" + clipId + "/editable.mp4");
        ffmpegProcessor.stage(rawPath, editableVideo);
        storageService.delete(rawRelativePath);

        clipService.markAwaitingEdit(clipId, "work/" + clipId + "/editable.mp4");
    }

    // ---- Fase 2: FINALIZE ----

    private void finalizeClip(UUID clipId) throws IOException {
        ClipService.FinalizeInput input = clipService.getFinalizeInput(clipId);

        Path editablePath = storageService.resolveLocalPath(input.editableFilePath());
        Path workVideo = storageService.resolveLocalPath("work/" + clipId + "/final.mp4");
        Path workThumb = storageService.resolveLocalPath("work/" + clipId + "/thumb.jpg");
        Path replacementAudioPath = input.replacementAudioTrackPath() != null
                ? storageService.resolveLocalPath(input.replacementAudioTrackPath()) : null;

        long outputDurationMs = Math.min(input.trimEndMs() - input.trimStartMs(), MAX_DURATION_MS);
        FfmpegProcessor.ProcessResult result = ffmpegProcessor.finalizeClip(editablePath, workVideo, workThumb,
                input.trimStartMs(), outputDurationMs, input.muteOriginalAudio(), replacementAudioPath,
                input.replacementAudioStartMs(), input.originalAudioVolume(), input.replacementAudioVolume());
        deleteQuietly(editablePath); // ya cumplió su función, sea cual sea el resultado de acá en más

        String contentHash = fileHasher.sha256Hex(workVideo);
        Optional<Clip> duplicate = clipService.findByContentHash(contentHash)
                .filter(existing -> !existing.getId().equals(clipId));
        if (duplicate.isPresent()) {
            log.warn("Clip {} es un duplicado exacto de {} (content_hash={}), se rechaza",
                    clipId, duplicate.get().getId(), contentHash);
            clipService.markRejected(clipId);
            deleteQuietly(workVideo);
            deleteQuietly(workThumb);
            return;
        }

        // duration/width/height/content_hash quedan igual aunque la moderación rechace el clip
        // (para que un intento de resubida del mismo archivo lo detecte como duplicado, ver
        // idx_clips_content_hash — exactamente el caso "bloquear reintentos tras un takedown"
        // de docs/SPEC.md sección 7).
        clipService.markReady(clipId, result.durationMs(), result.width(), result.height(), contentHash);

        // Pipeline de moderación (sección 10): corre siempre, para OWN_UPLOAD y EXTERNAL_CAPTURE
        // por igual, antes de que el clip pueda quedar público.
        Path framesDir = storageService.resolveLocalPath("work/" + clipId + "/frames");
        List<Path> frames = ffmpegProcessor.extractFrames(workVideo, framesDir, MODERATION_MAX_FRAMES);
        List<ModerationService.FrameSample> samples = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            samples.add(new ModerationService.FrameSample(frames.get(i), i * 1000));
        }

        ModerationService.Outcome outcome = moderationService.moderate(clipId, samples);
        deleteRecursively(framesDir); // nunca se retienen los frames, matcheen o no (sección 10)

        if (outcome == ModerationService.Outcome.REJECTED) {
            log.warn("Clip {} rechazado por moderación", clipId);
            clipService.markRejected(clipId);
            deleteQuietly(workVideo);
            deleteQuietly(workThumb);
            return;
        }

        // Solo se copia a "public/" (servido por /media/clips/**, ver WebConfig) una vez que
        // pasó moderación de verdad.
        Path publicVideo = storageService.resolveLocalPath("public/" + clipId + "/final.mp4");
        Path publicThumb = storageService.resolveLocalPath("public/" + clipId + "/thumb.jpg");
        Files.createDirectories(publicVideo.getParent());
        Files.move(workVideo, publicVideo, StandardCopyOption.REPLACE_EXISTING);
        Files.move(workThumb, publicThumb, StandardCopyOption.REPLACE_EXISTING);

        clipService.markPublished(clipId, "public/" + clipId + "/final.mp4", "public/" + clipId + "/thumb.jpg");
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("No se pudo limpiar {}", dir, e);
        }
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
