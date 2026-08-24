package com.clipshare.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invoca el binario standalone de yt-dlp (ver backend/Dockerfile y Dockerfile.worker) para
 * descargar server-side el video/audio de una plataforma externa. Decisión de producto
 * tomada a propósito por el usuario sabiendo que pisa los ToS de YouTube/Vimeo/Twitch —
 * reemplaza el mecanismo anterior de grabar la pantalla (getDisplayMedia), que quedaba con
 * calidad inaceptable en la práctica.
 *
 * Componente plano (no atado a ningún perfil de Spring): lo usa tanto el backend (chequeo
 * síncrono de metadata antes de encolar una descarga completa, y descarga de audio de
 * reemplazo — corta, entra en el tiempo de un request HTTP normal) como el worker (descarga
 * completa del video, que sí puede tardar).
 */
@Component
public class YtDlpClient {

    private static final Logger log = LoggerFactory.getLogger(YtDlpClient.class);
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    // Reintentos pensados sobre todo para TikTok (ver ClipPlatform): su protección anti-bot es
    // flaky por naturaleza — probado a mano contra el mismo link real, un intento con
    // curl_cffi instalado (ver Dockerfile.worker) puede devolver 403 o fallar el parseo de la
    // página, y el siguiente intento simplemente anda, sin cambiar nada más. 3 intentos en
    // total (2 reintentos), con una pausa corta entre uno y otro para no encimar el mismo
    // fingerprint/cookie que ya falló.
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    /** durationMs nullable a propósito: algunas plataformas (Instagram reels con entrega DASH,
     * confirmado probando en vivo) no exponen duración en la metadata que devuelve yt-dlp
     * aunque el video sea perfectamente normal, no una transmisión en vivo — no hay forma de
     * distinguir "no la sabemos" de "en vivo" solo con esto. Cuando es null, el caller no debe
     * bloquear el import por esto: la duración real se termina validando igual después de
     * descargar (ver ClipProcessingWorker.stageClip, con ffprobe sobre el archivo real). */
    public record Metadata(Long durationMs, String title) {
    }

    public static class YtDlpException extends RuntimeException {
        private final boolean retryable;

        public YtDlpException(String message) {
            this(message, false);
        }

        public YtDlpException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        /** true = probablemente transitorio (ver MAX_ATTEMPTS) — false = el resultado no va a
         * cambiar en un reintento (video privado, requiere login, no existe, etc.). */
        public boolean isRetryable() {
            return retryable;
        }
    }

    /** Solo pide metadata (sin descargar nada) — rápido, pensado para chequear la duración
     * ANTES de encolar la descarga completa (docs/SPEC.md: máximo 10 minutos de fuente).
     * --no-playlist es crítico acá (se me había pasado en la versión original): un link que
     * además de un video individual carga una referencia de playlist/radio (ej.
     * "?v=xxx&list=RDxxx&start_radio=1", típico de "reproducir siguiente" de YouTube) hace que
     * yt-dlp, sin esta bandera, imprima duration+title de TODOS los videos de la lista — puede
     * ser un mix de cientos, tarda de sobra para pasarse el timeout de METADATA_TIMEOUT y
     * termina en YtDlpException, con el título cayendo al fallback (la URL cruda, ver
     * AudioTrackService/ClipService.importFromLink). downloadVideo/downloadAudio ya tenían
     * esta bandera; acá faltaba. */
    public Metadata fetchMetadata(String url) {
        String output = runWithRetries(List.of("yt-dlp", "--print", "duration", "--print", "title", "--no-warnings", "--no-playlist", url),
                METADATA_TIMEOUT);
        String[] lines = output.strip().split("\n", 2);
        if (lines.length < 2) {
            throw new YtDlpException("No se pudo leer la información del video");
        }
        // "NA" es lo que imprime yt-dlp cuando no puede determinar la duración — puede ser una
        // transmisión en vivo, pero también un video grabado normal cuya plataforma de origen
        // simplemente no expone esa metadata (confirmado con reels de Instagram entregados por
        // DASH). No se trata como error: se deja pasar con duración desconocida, ver Metadata.
        try {
            double durationSeconds = Double.parseDouble(lines[0].strip());
            return new Metadata(Math.round(durationSeconds * 1000), lines[1].strip());
        } catch (NumberFormatException e) {
            return new Metadata(null, lines[1].strip());
        }
    }

    /** Descarga el mejor formato disponible hasta 720p, mp4 con audio incluido. 720p (no la
     * resolución máxima) a propósito: el clip final es de a lo sumo 20s recortados de un
     * apartado ≤10min — no hay beneficio real en bajar 4K para eso, solo más tiempo/espacio. */
    public void downloadVideo(String url, Path output) {
        runWithRetries(List.of("yt-dlp",
                "-f", "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/b[ext=mp4]/best",
                "-o", output.toString(),
                "--no-warnings", "--no-progress", "--no-playlist",
                url), DOWNLOAD_TIMEOUT);
    }

    /** Extrae solo el audio a m4a — para la pista de reemplazo importada por link
     * (docs/SPEC.md: mismo criterio de riesgo ya aceptado para el video). */
    public void downloadAudio(String url, Path output) {
        runWithRetries(List.of("yt-dlp", "-x", "--audio-format", "m4a",
                "-o", output.toString(),
                "--no-warnings", "--no-progress", "--no-playlist",
                url), DOWNLOAD_TIMEOUT);
    }

    private String runWithRetries(List<String> cmd, Duration timeout) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return run(cmd, timeout);
            } catch (YtDlpException e) {
                boolean lastAttempt = attempt == MAX_ATTEMPTS;
                if (!e.isRetryable() || lastAttempt) throw e;
                log.warn("yt-dlp falló (intento {}/{}), reintentando: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleepQuietly(RETRY_DELAY);
            }
        }
        throw new IllegalStateException("unreachable"); // el loop de arriba siempre retorna o lanza antes de salir
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String run(List<String> cmd, Duration timeout) {
        log.debug("Ejecutando: {}", String.join(" ", cmd));
        Process process;
        try {
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new YtDlpException("No se pudo iniciar yt-dlp: " + e.getMessage());
        }

        // stdout y stderr se leen en threads separados, no uno-tras-otro: yt-dlp puede
        // escribir bastante en los dos al mismo tiempo (incluso con --no-progress), y leer
        // secuencialmente arriesga un deadlock clásico si uno de los dos pipes se llena
        // mientras el proceso hijo está bloqueado esperando que alguien lo vacíe.
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        Thread stdoutReader = readerThread(process.getInputStream(), stdoutBuilder);
        Thread stderrReader = readerThread(process.getErrorStream(), stderrBuilder);
        stdoutReader.start();
        stderrReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new YtDlpException("Interrumpido descargando el video"); // no retryable: el hilo fue interrumpido a propósito
        }
        if (!finished) {
            process.destroyForcibly();
            // retryable=true: un timeout puntual no dice que la fuente esté rota, ver
            // MAX_ATTEMPTS — sobre todo relevante para TikTok, donde resolver el JS challenge a
            // veces tarda de más.
            throw new YtDlpException("El video tardó demasiado en descargarse", true);
        }
        joinQuietly(stdoutReader);
        joinQuietly(stderrReader);
        String stdout = stdoutBuilder.toString();
        String stderr = stderrBuilder.toString();

        if (process.exitValue() != 0) {
            log.warn("yt-dlp salió con código {}: {}", process.exitValue(), stderr);
            throw new YtDlpException(describeFailure(stderr), isRetryableFailure(stderr));
        }
        return stdout;
    }

    /** false = errores definitivos, donde reintentar no cambiaría nada (el video sigue siendo
     * privado/inexistente en el siguiente intento) — cualquier otra cosa se trata como
     * transitoria, pensado sobre todo para el anti-bot de TikTok (ver MAX_ATTEMPTS): a veces
     * devuelve 403 o falla el parseo de la página, y el intento siguiente simplemente anda. */
    private boolean isRetryableFailure(String stderr) {
        return !(stderr.contains("logged-in") || stderr.contains("cookies")
                || stderr.contains("Private video") || stderr.contains("private")
                || stderr.contains("Video unavailable") || stderr.contains("not available"));
    }

    private Thread readerThread(InputStream in, StringBuilder target) {
        Thread thread = new Thread(() -> {
            try {
                target.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // El proceso murió/se cerró el stream — no hay más para leer, no es un error propio.
            }
        });
        thread.setDaemon(true);
        return thread;
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** yt-dlp devuelve mensajes de error técnicos/largos — se traduce a algo legible para
     * mostrarle al usuario, sin perder la causa real en los logs (arriba, vía log.warn). */
    private String describeFailure(String stderr) {
        if (stderr.contains("logged-in") || stderr.contains("cookies")) {
            return "No pudimos acceder a ese video — puede requerir haber iniciado sesión en la plataforma de origen";
        }
        if (stderr.contains("Private video") || stderr.contains("private")) {
            return "Ese video es privado";
        }
        if (stderr.contains("Video unavailable") || stderr.contains("not available")) {
            return "Ese video no está disponible";
        }
        return "No pudimos descargar ese video";
    }
}
