package com.clipshare.comment;

import com.clipshare.config.ApiException;
import com.clipshare.moderation.CsamHashService;
import com.clipshare.moderation.NcmecReportClient;
import com.clipshare.moderation.StrikeService;
import com.clipshare.storage.FileHasher;
import com.clipshare.storage.StorageService;
import com.clipshare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Sube y modera una imagen adjunta a un comentario (docs/SPEC.md sección 11.9). Desviación
 * deliberada del spec: el texto dice "se encola en el mismo worker de hashing perceptual
 * usado para los frames de video" (asíncrono); acá el chequeo corre síncrono, en el propio
 * request. Verificar UNA imagen suelta no tiene el costo de un pipeline de ffmpeg — encolarlo
 * solo agregaría latencia de otra vuelta de Redis + polling sin ningún beneficio real hasta
 * que haya un CsamHashService real (que sí podría justificar volver a esto asíncrono). El
 * pipeline en sí (CsamHashService → csam_hash_matches/strike/NCMEC) queda igual de conectado
 * que el de clips, solo cambia cuándo se invoca.
 */
@Service
public class CommentAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(CommentAttachmentService.class);
    private static final long MAX_IMAGE_SIZE_BYTES = 8L * 1024 * 1024; // 8MB, no expuesto por env var — ver docs/SPEC.md sección 13
    private static final Map<String, String> EXTENSION_BY_MIME_TYPE = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "image/gif", ".gif"
    );

    private final CommentAttachmentRepository attachmentRepository;
    private final StorageService storageService;
    private final FileHasher fileHasher;
    private final CsamHashService csamHashService;
    private final NcmecReportClient ncmecReportClient;
    private final StrikeService strikeService;

    public CommentAttachmentService(CommentAttachmentRepository attachmentRepository, StorageService storageService,
                                     FileHasher fileHasher, CsamHashService csamHashService,
                                     NcmecReportClient ncmecReportClient, StrikeService strikeService) {
        this.attachmentRepository = attachmentRepository;
        this.storageService = storageService;
        this.fileHasher = fileHasher;
        this.csamHashService = csamHashService;
        this.ncmecReportClient = ncmecReportClient;
        this.strikeService = strikeService;
    }

    @Transactional
    public UUID uploadImage(User uploader, MultipartFile file) {
        if (!uploader.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Verificá tu email para adjuntar imágenes en comentarios");
        }
        String extension = validateAndExtractExtension(file);

        // Token de storage generado acá, DISTINTO del id que Postgres/Hibernate le va a
        // asignar a la fila recién al insertarla — no necesitan coincidir, imagePath queda
        // guardado en la fila y de ahí se arma la URL pública (ver AttachmentResponse), nunca
        // se reconstruye a partir del id. Guardar la fila antes de conocer image_path violaría
        // chk_attachment_payload (exige image_path no nulo para IMAGE) apenas Hibernate haga
        // el flush — evitamos ese estado intermedio del todo en vez de pelear con el momento
        // exacto del flush.
        UUID storageToken = UUID.randomUUID();
        String relativePath = "attachments/" + storageToken + "/original" + extension;

        try (InputStream in = file.getInputStream()) {
            storageService.store(relativePath, in);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "No se pudo guardar la imagen");
        }

        Path localPath = storageService.resolveLocalPath(relativePath);
        try {
            String contentHash = fileHasher.sha256Hex(localPath);
            CsamHashService.FrameCheckResult result = csamHashService.checkFrame(localPath);

            if (result.matched()) {
                deleteQuietly(relativePath);
                ncmecReportClient.report(storageToken, result.matchedHashSource());
                strikeService.recordCsamStrike(uploader, null);
                throw ApiException.forbidden("ATTACHMENT_REJECTED", "La imagen no pudo publicarse");
            }

            CommentAttachment attachment = CommentAttachment.approvedImage(uploader, relativePath, contentHash, file.getContentType());
            attachmentRepository.save(attachment);
            return attachment.getId();
        } catch (IOException e) {
            deleteQuietly(relativePath);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MODERATION_ERROR", "No se pudo verificar la imagen");
        }
    }

    /** Busca un adjunto de imagen ya subido y listo para usarse en un comentario nuevo: debe
     * ser del mismo usuario y no estar ya asociado a otro comentario. */
    CommentAttachment resolvePendingImage(UUID attachmentId, User requester) {
        CommentAttachment attachment = attachmentRepository.findByIdWithUploader(attachmentId)
                .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND", "Adjunto no encontrado"));
        if (attachment.getAttachmentType() != AttachmentType.IMAGE) {
            throw ApiException.badRequest("INVALID_ATTACHMENT_TYPE", "El adjunto no es una imagen");
        }
        if (!attachment.getUploadedBy().getId().equals(requester.getId())) {
            throw ApiException.forbidden("NOT_ATTACHMENT_OWNER", "No podés usar un adjunto de otro usuario");
        }
        if (attachment.getComment() != null) {
            throw ApiException.badRequest("ATTACHMENT_ALREADY_USED", "Este adjunto ya se usó en otro comentario");
        }
        return attachment;
    }

    private String validateAndExtractExtension(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "La imagen supera el tamaño máximo permitido (8MB)");
        }
        String extension = EXTENSION_BY_MIME_TYPE.get(file.getContentType());
        if (extension == null) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "La imagen debe ser JPEG, PNG, WEBP o GIF");
        }
        return extension;
    }

    private void deleteQuietly(String relativePath) {
        try {
            storageService.delete(relativePath);
        } catch (IOException e) {
            log.warn("No se pudo borrar el archivo temporal de adjunto {}", relativePath, e);
        }
    }
}
