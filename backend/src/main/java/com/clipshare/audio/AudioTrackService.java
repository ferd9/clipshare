package com.clipshare.audio;

import com.clipshare.config.ApiException;
import com.clipshare.storage.FileHasher;
import com.clipshare.storage.StorageService;
import com.clipshare.user.User;
import com.clipshare.worker.FfmpegProcessor;
import com.clipshare.worker.YtDlpClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Pista de audio de reemplazo para un clip (docs/SPEC.md sección 1). A diferencia del pipeline
 * de video, corre entera SÍNCRONA dentro del request HTTP (sin cola/worker): un archivo de
 * audio de hasta 10 minutos se procesa en segundos, no vale la pena la complejidad de una fase
 * async para esto. Requiere ffmpeg/yt-dlp en el propio contenedor backend (ver backend/Dockerfile).
 *
 * TODO(v2): sin pipeline de detección de copyright para audio todavía — se aprueba directo
 * (ver constructor de AudioTrack), igual que MockCsamHashService documenta honestamente que
 * no hace una verificación real todavía. Aceptable para este alcance: el mismo riesgo de ToS
 * ya fue asumido explícitamente para el video.
 */
@Service
public class AudioTrackService {

    /** Mismo criterio que la fuente de video (docs/SPEC.md): hasta 10 minutos. */
    private static final long MAX_AUDIO_DURATION_MS = 10 * 60 * 1000L;

    private final AudioTrackRepository audioTrackRepository;
    private final StorageService storageService;
    private final YtDlpClient ytDlpClient;
    private final FileHasher fileHasher;
    private final FfmpegProcessor ffmpegProcessor;

    public AudioTrackService(AudioTrackRepository audioTrackRepository, StorageService storageService,
                              YtDlpClient ytDlpClient, FileHasher fileHasher, FfmpegProcessor ffmpegProcessor) {
        this.audioTrackRepository = audioTrackRepository;
        this.storageService = storageService;
        this.ytDlpClient = ytDlpClient;
        this.fileHasher = fileHasher;
        this.ffmpegProcessor = ffmpegProcessor;
    }

    @Transactional
    public AudioTrack uploadFile(User owner, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío");
        }
        // A propósito, sin gate por Content-Type acá (a diferencia de ClipService.validateFile
        // para video): el Content-Type que manda el cliente para audio es notoriamente
        // inconsistente entre navegadores/SO para formatos como .m4a — algunos mandan
        // "audio/x-m4a", otros "audio/mp4", y no pocos "application/octet-stream" sin más
        // (confirmado probando con curl). Rechazar por esa cabecera produciría falsos
        // negativos con archivos de audio perfectamente válidos. ffprobe, un poco más abajo,
        // ya es la validación real y autoritativa — si no es audio/video decodificable, falla
        // ahí con un 422 INVALID_AUDIO, sin necesidad de adivinar antes por el Content-Type.
        String relativePath = "audio/" + UUID.randomUUID() + extractExtension(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            storageService.store(relativePath, in);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "No se pudo guardar el archivo");
        }
        String title = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio";
        return finalizeAudioFile(owner, relativePath, title, null);
    }

    /** Mismo criterio de riesgo ya aceptado para el video (docs/SPEC.md): descarga server-side. */
    @Transactional
    public AudioTrack importFromLink(User owner, String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw ApiException.badRequest("INVALID_SOURCE_URL", "Falta la URL de origen");
        }
        // Mismo patrón que ClipService.importFromLink: el título real (no la URL cruda) sale
        // de la metadata de yt-dlp — mostrar el link entero como "nombre" del audio en el
        // frontend no era legible ni decía nada útil.
        String title = sourceUrl;
        try {
            title = ytDlpClient.fetchMetadata(sourceUrl).title();
        } catch (YtDlpClient.YtDlpException ignored) {
            // Si falla el pre-chequeo de metadata, se sigue igual con la URL como título de
            // respaldo — la descarga de abajo es la que de verdad decide si esto funciona o no.
        }

        String relativePath = "audio/" + UUID.randomUUID() + ".m4a";
        Path localPath = storageService.resolveLocalPath(relativePath);
        try {
            ytDlpClient.downloadAudio(sourceUrl, localPath);
        } catch (YtDlpClient.YtDlpException e) {
            throw ApiException.badRequest("SOURCE_UNAVAILABLE", e.getMessage());
        }
        return finalizeAudioFile(owner, relativePath, title, sourceUrl);
    }

    private AudioTrack finalizeAudioFile(User owner, String relativePath, String title, String sourceUrl) {
        Path localPath = storageService.resolveLocalPath(relativePath);

        FfmpegProcessor.ProbeResult probe;
        try {
            probe = ffmpegProcessor.probe(localPath);
        } catch (IOException e) {
            deleteQuietly(relativePath);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AUDIO", "No se pudo leer el archivo de audio");
        }
        if (probe.durationMs() > MAX_AUDIO_DURATION_MS) {
            deleteQuietly(relativePath);
            throw ApiException.badRequest("AUDIO_TOO_LONG", "El audio no puede superar los 10 minutos");
        }

        String contentHash;
        try {
            contentHash = fileHasher.sha256Hex(localPath);
        } catch (IOException e) {
            deleteQuietly(relativePath);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "No se pudo procesar el archivo de audio");
        }

        Optional<AudioTrack> existing = audioTrackRepository.findByContentHash(contentHash);
        if (existing.isPresent()) {
            deleteQuietly(relativePath); // dedupe: no se guarda dos veces el mismo audio
            AudioTrack track = existing.get();
            // Autocorrección: pistas importadas por link ANTES de que se empezara a pedir el
            // título real vía yt-dlp quedaron con la URL cruda como "título" (ver el fix más
            // arriba en importFromLink) — si el título recién calculado es distinto del que
            // ya tenía guardado, se corrige acá mismo, sin depender de que alguien vuelva a
            // subir el archivo desde cero para que se actualice.
            if (title != null && !title.equals(track.getTitle())) {
                track.setTitle(title);
            }
            return track;
        }

        return audioTrackRepository.save(
                new AudioTrack(owner, title, relativePath, (int) probe.durationMs(), contentHash, sourceUrl));
    }

    /** Ownership check — en esta primera versión solo el propio uploader puede referenciar su
     * pista al crear un clip (biblioteca pública de "sonidos" reusables queda para más adelante,
     * ver comentario en AudioTrack.usageCount). */
    @Transactional
    public AudioTrack getOwnedTrack(UUID id, User requester) {
        AudioTrack track = audioTrackRepository.findByIdWithUploader(id)
                .orElseThrow(() -> ApiException.notFound("AUDIO_NOT_FOUND", "Audio no encontrado"));
        if (track.getUploadedBy() == null || !track.getUploadedBy().getId().equals(requester.getId())) {
            throw ApiException.notFound("AUDIO_NOT_FOUND", "Audio no encontrado");
        }
        return track;
    }

    private void deleteQuietly(String relativePath) {
        try {
            storageService.delete(relativePath);
        } catch (IOException ignored) {
            // best-effort — no tapar el error real (ya se está por lanzar una ApiException)
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) return ".m4a";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return ".m4a";
        String ext = originalFilename.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".m4a";
    }
}
