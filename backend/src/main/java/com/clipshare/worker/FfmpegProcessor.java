package com.clipshare.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Invoca ffmpeg/ffprobe vía ProcessBuilder (ver docs/SPEC.md sección 3). Recorta a
 * {@code maxDurationMs} solo si el original la excede, normaliza códec/formato y genera
 * una miniatura. Corre exclusivamente en el contenedor `worker` (el único con ffmpeg
 * instalado, ver backend/Dockerfile.worker).
 */
@Component
public class FfmpegProcessor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegProcessor.class);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper objectMapper;

    public FfmpegProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ProbeResult(long durationMs, Integer width, Integer height) {
    }

    public record ProcessResult(long durationMs, Integer width, Integer height) {
    }

    /**
     * @param trimStartMs offset dentro de {@code input} desde donde arranca el clip final —
     *                     0 para OWN_UPLOAD (todo el archivo); en EXTERNAL_CAPTURE puede ser
     *                     >0 si el usuario recortó su grabación en el editor (docs/SPEC.md
     *                     sección 9, V7__clip_trim.sql).
     * @param outputDurationMs duración deseada del clip final (ya acotada a ≤20s por el
     *                          caller) — si el archivo de entrada es más corto que
     *                          {@code trimStartMs + outputDurationMs}, ffmpeg simplemente
     *                          corta en EOF, no hace falta manejarlo como caso especial.
     */
    public ProcessResult process(Path input, Path outputVideo, Path outputThumbnail,
                                  long trimStartMs, long outputDurationMs) throws IOException {
        Files.createDirectories(outputVideo.getParent());
        Files.createDirectories(outputThumbnail.getParent());

        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-i", input.toString()));
        if (trimStartMs > 0) {
            // Seek DESPUÉS de -i (no antes): más lento pero preciso a nivel de frame — como
            // igual se re-codifica todo (no hay -c copy), el costo extra es insignificante
            // para clips de a lo sumo 20s.
            cmd.addAll(List.of("-ss", formatSeconds(trimStartMs)));
        }
        cmd.addAll(List.of("-t", formatSeconds(outputDurationMs)));
        cmd.addAll(List.of(
                "-vf", "scale='min(1280,iw)':-2",
                // pix_fmt yuv420p a propósito: sin esto, libx264 a veces preserva el formato
                // de color de la fuente (ej. yuv444p/RGB en screen recordings o video sintético)
                // y ningún navegador puede reproducir esos perfiles de H.264 — el <video> se
                // queda colgado en HAVE_NOTHING sin lanzar ningún error legible.
                "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                outputVideo.toString()));
        runProcess(cmd, false);

        ProbeResult finalProbe = probe(outputVideo);

        runProcess(List.of("ffmpeg", "-y", "-i", outputVideo.toString(),
                "-ss", "0.1", "-frames:v", "1", outputThumbnail.toString()), false);

        return new ProcessResult(finalProbe.durationMs(), finalProbe.width(), finalProbe.height());
    }

    private String formatSeconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0);
    }

    /**
     * Extrae hasta {@code maxFrames} frames representativos (uno por segundo) para el
     * pipeline de moderación (docs/SPEC.md sección 10, paso 2). Devuelve las rutas en orden.
     */
    public List<Path> extractFrames(Path input, Path outputDir, int maxFrames) throws IOException {
        Files.createDirectories(outputDir);
        runProcess(List.of("ffmpeg", "-y", "-i", input.toString(),
                "-vf", "fps=1", "-frames:v", String.valueOf(maxFrames),
                outputDir.resolve("frame_%03d.jpg").toString()), false);

        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("frame_"))
                    .sorted()
                    .toList();
        }
    }

    ProbeResult probe(Path input) throws IOException {
        List<String> cmd = List.of("ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", input.toString());
        String json = runProcess(cmd, true);
        JsonNode root = objectMapper.readTree(json);

        double durationSeconds = root.path("format").path("duration").asDouble(0);
        Integer width = null;
        Integer height = null;
        for (JsonNode stream : root.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                width = stream.path("width").asInt(0);
                height = stream.path("height").asInt(0);
                if (durationSeconds <= 0) {
                    durationSeconds = stream.path("duration").asDouble(0);
                }
                break;
            }
        }

        if (durationSeconds <= 0) {
            throw new FfmpegException("No se pudo determinar la duración de " + input);
        }
        return new ProbeResult(Math.round(durationSeconds * 1000), width, height);
    }

    private String runProcess(List<String> cmd, boolean captureOutput) throws IOException {
        log.debug("Ejecutando: {}", String.join(" ", cmd));
        ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(!captureOutput);
        Process process = builder.start();

        String stdout = readAll(process.getInputStream());
        String stderr = captureOutput ? readAll(process.getErrorStream()) : "";

        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new FfmpegException("Interrumpido ejecutando: " + cmd.get(0));
        }
        if (!finished) {
            process.destroyForcibly();
            throw new FfmpegException("Timeout ejecutando: " + cmd.get(0));
        }
        if (process.exitValue() != 0) {
            throw new FfmpegException("%s salió con código %d: %s"
                    .formatted(cmd.get(0), process.exitValue(), captureOutput ? stderr : stdout));
        }
        return stdout;
    }

    private String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
