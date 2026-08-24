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

    public record ProbeResult(long durationMs, Integer width, Integer height, boolean hasAudio) {
    }

    public record ProcessResult(long durationMs, Integer width, Integer height) {
    }

    // trunc(.../2)*2 en el ANCHO, no solo "-2" en el alto: yuv420p exige ancho Y alto pares
    // (submuestreo de croma) — libx264 tira "width not divisible by 2" y no escribe nada si
    // el ancho de entrada es impar (pasó de verdad probando en vivo el recorte por
    // proporción del editor anterior). pix_fmt yuv420p a propósito: sin esto, libx264 a
    // veces preserva el formato de color de la fuente (ej. yuv444p/RGB) y ningún navegador
    // puede reproducir esos perfiles de H.264 — el <video> se queda colgado en HAVE_NOTHING
    // sin lanzar ningún error legible.
    private static final String SCALE_FILTER = "scale='trunc(min(1280,iw)/2)*2':-2";

    /**
     * Fase 1 (docs/SPEC.md, import server-side): normaliza la fuente completa (subida propia
     * o ya descargada por YtDlpClient) a un mp4 h264/aac reproducible en cualquier navegador,
     * sin recortar todavía — es el archivo que el editor de recorte muestra en el
     * {@code <video>} del frontend para elegir el rango final.
     */
    public ProbeResult stage(Path input, Path outputVideo) throws IOException {
        Files.createDirectories(outputVideo.getParent());
        List<String> cmd = List.of("ffmpeg", "-y", "-i", input.toString(),
                "-vf", SCALE_FILTER,
                "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                outputVideo.toString());
        runProcess(cmd, false);
        return probe(outputVideo);
    }

    /**
     * Fase 2: recorta la fuente ya normalizada ({@link #stage}) al rango elegido, y aplica
     * silenciar/reemplazar audio si corresponde (docs/SPEC.md sección 1: "el clip final
     * puede llevar una pista de audio superpuesta").
     *
     * @param trimStartMs offset dentro de {@code editableVideo} desde donde arranca el clip.
     * @param outputDurationMs duración final deseada (ya acotada al máximo por el caller,
     *                          ver ClipService.MAX_CLIP_DURATION_MS).
     * @param muteOriginalAudio si es true y NO hay reemplazo, el clip queda mudo; si hay
     *                          reemplazo, decide entre REEMPLAZAR (true: se descarta el audio
     *                          original, solo se oye replacementAudio) o MEZCLAR (false: se
     *                          escuchan las dos pistas juntas, ver {@code amix} más abajo) —
     *                          docs/SPEC.md sección 1: "reemplazar, o mezclar con el original".
     * @param replacementAudio pista de reemplazo ya resuelta (o null = sin reemplazo).
     * @param replacementAudioStartMs desde dónde arrancar {@code replacementAudio} — el
     *                                fragmento elegido en el editor es independiente del
     *                                recorte del video (un mismo "sonido" reusado en otro clip
     *                                puede arrancar en otro punto, ver Clip.replacementAudioStartMs).
     *                                Sin uso si replacementAudio es null.
     * @param originalAudioVolume nivel (0.0-1.0) elegido en el editor para el audio original —
     *                             ver Clip.originalAudioVolume. Se aplica siempre que esa pista
     *                             termine sonando (mezcla o "mantener original"), no solo al mezclar.
     * @param replacementAudioVolume ídem para replacementAudio — ver Clip.replacementAudioVolume.
     */
    public ProcessResult finalizeClip(Path editableVideo, Path outputVideo, Path outputThumbnail,
                                       long trimStartMs, long outputDurationMs,
                                       boolean muteOriginalAudio, Path replacementAudio,
                                       long replacementAudioStartMs,
                                       double originalAudioVolume, double replacementAudioVolume) throws IOException {
        Files.createDirectories(outputVideo.getParent());
        Files.createDirectories(outputThumbnail.getParent());
        boolean hasReplacementAudio = replacementAudio != null;
        // Si el usuario pidió mezclar pero la fuente resulta no tener audio propio (posible:
        // stage() no fuerza una pista de audio si el original no traía ninguna), no hay nada
        // que mezclar — se cae a reemplazar sin más, en vez de romper el filtro de abajo
        // (amix con un input "0:a" inexistente no compila).
        boolean originalHasAudio = probe(editableVideo).hasAudio();
        boolean mixAudio = hasReplacementAudio && !muteOriginalAudio && originalHasAudio;
        boolean replaceOnly = hasReplacementAudio && !mixAudio;
        boolean keepOriginalOnly = !hasReplacementAudio && !muteOriginalAudio && originalHasAudio;

        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y"));
        if (trimStartMs > 0) {
            // -ss ANTES del -i que corresponde (no después): un -ss "global" (antes de
            // cualquier -i) se aplicaría a TODOS los inputs por igual, pero acá cada uno puede
            // necesitar su propio offset — el de reemplazo tiene el suyo propio más abajo.
            cmd.addAll(List.of("-ss", formatSeconds(trimStartMs)));
        }
        cmd.addAll(List.of("-i", editableVideo.toString()));
        if (hasReplacementAudio) {
            if (replacementAudioStartMs > 0) {
                cmd.addAll(List.of("-ss", formatSeconds(replacementAudioStartMs)));
            }
            cmd.addAll(List.of("-i", replacementAudio.toString()));
        }

        if (mixAudio) {
            // El volumen de cada pista se aplica ANTES de amix, no después: amix normaliza sus
            // entradas por igual, así que ajustar el volumen del resultado ya mezclado no
            // permitiría subir una pista relativo a la otra — hay que escalar cada una por
            // separado (volume=X) y recién ahí mezclarlas.
            // duration=longest (no "shortest"): que amix no corte antes de tiempo si una de
            // las dos pistas es más corta que la otra — el -t de más abajo ya se encarga del
            // largo final real, acá solo importa que ninguna se trunque antes de esa marca.
            String filter = "[0:a]volume=%s[va0];[1:a]volume=%s[va1];[va0][va1]amix=inputs=2:duration=longest:dropout_transition=0[aout]"
                    .formatted(formatVolume(originalAudioVolume), formatVolume(replacementAudioVolume));
            cmd.addAll(List.of("-filter_complex", filter));
            cmd.addAll(List.of("-map", "0:v", "-map", "[aout]"));
        } else {
            cmd.addAll(List.of("-map", "0:v"));
            if (replaceOnly) {
                cmd.addAll(List.of("-map", "1:a", "-shortest", "-filter:a", "volume=" + formatVolume(replacementAudioVolume)));
            } else if (keepOriginalOnly) {
                cmd.addAll(List.of("-map", "0:a", "-filter:a", "volume=" + formatVolume(originalAudioVolume)));
            } else if (!muteOriginalAudio) {
                cmd.addAll(List.of("-map", "0:a?")); // "?" = opcional, no falla si la fuente no tiene audio (sin volumen que aplicar)
            }
            // muteOriginalAudio && !hasReplacementAudio: no se mapea ningún audio -> el clip queda mudo.
        }

        cmd.addAll(List.of("-t", formatSeconds(outputDurationMs)));
        cmd.addAll(List.of("-vf", SCALE_FILTER, "-pix_fmt", "yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23"));
        if (!muteOriginalAudio || hasReplacementAudio) {
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
        }
        cmd.addAll(List.of("-movflags", "+faststart", outputVideo.toString()));
        runProcess(cmd, false);

        ProbeResult finalProbe = probe(outputVideo);
        runProcess(List.of("ffmpeg", "-y", "-i", outputVideo.toString(),
                "-ss", "0.1", "-frames:v", "1", outputThumbnail.toString()), false);
        return new ProcessResult(finalProbe.durationMs(), finalProbe.width(), finalProbe.height());
    }

    private String formatSeconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0);
    }

    private String formatVolume(double volume) {
        return String.format(java.util.Locale.ROOT, "%.2f", volume);
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

    /** Público: también lo usa AudioTrackService (otro paquete) para validar/duración de audio subido. */
    public ProbeResult probe(Path input) throws IOException {
        List<String> cmd = List.of("ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", input.toString());
        String json = runProcess(cmd, true);
        JsonNode root = objectMapper.readTree(json);

        double durationSeconds = root.path("format").path("duration").asDouble(0);
        Integer width = null;
        Integer height = null;
        boolean hasAudio = false;
        for (JsonNode stream : root.path("streams")) {
            String codecType = stream.path("codec_type").asText();
            if ("video".equals(codecType)) {
                width = stream.path("width").asInt(0);
                height = stream.path("height").asInt(0);
                if (durationSeconds <= 0) {
                    durationSeconds = stream.path("duration").asDouble(0);
                }
            } else if ("audio".equals(codecType)) {
                hasAudio = true;
            }
        }

        if (durationSeconds <= 0) {
            throw new FfmpegException("No se pudo determinar la duración de " + input);
        }
        return new ProbeResult(Math.round(durationSeconds * 1000), width, height, hasAudio);
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
