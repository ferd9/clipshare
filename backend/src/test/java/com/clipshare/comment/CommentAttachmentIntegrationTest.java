package com.clipshare.comment;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ClipSourceType;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 6b (docs/SPEC.md sección 11.9): adjuntos de comentario — imagen (con verificación
 * CSAM síncrona, ver CommentAttachmentService), referencia a otro clip, y enlace externo con
 * chequeo de dominio bloqueado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CommentAttachmentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.storage.local-path", storageDir::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ClipRepository clipRepository;
    @Autowired CommentAttachmentRepository attachmentRepository;
    @Autowired BlockedLinkDomainRepository blockedLinkDomainRepository;

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1","displayName":"User"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginResponse).get("accessToken").asText();
    }

    private void verifyEmail(String email) {
        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
    }

    private UUID publishedClip(String ownerEmail) throws Exception {
        registerAndLogin(ownerEmail);
        User owner = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(ownerEmail).orElseThrow();
        Clip clip = new Clip(owner, ClipSourceType.OWN_UPLOAD);
        clip.setModerationStatus(ModerationStatus.PUBLISHED);
        clipRepository.save(clip);
        return clip.getId();
    }

    private String uploadImage(String token) throws Exception {
        MockMultipartFile image = new MockMultipartFile("file", "pic.jpg", "image/jpeg", "fake-image-bytes".getBytes());
        String response = mockMvc.perform(multipart("/api/comments/attachments/image").file(image)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("attachmentId").asText();
    }

    @Test
    void unverifiedEmailCannotUploadAnImage() throws Exception {
        String email = "unverified-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);
        MockMultipartFile image = new MockMultipartFile("file", "pic.jpg", "image/jpeg", "bytes".getBytes());

        mockMvc.perform(multipart("/api/comments/attachments/image").file(image)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void verifiedUserCanUploadAndAttachAnImageToAComment() throws Exception {
        UUID clipId = publishedClip("owner-" + System.nanoTime() + "@example.com");
        String email = "uploader-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);
        verifyEmail(email);

        String attachmentId = uploadImage(token);

        // El id del adjunto y el "token" de storage en la ruta del archivo son
        // deliberadamente independientes (ver CommentAttachmentService) — no se assertea que
        // coincidan, solo la forma de la URL.
        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"Mirá esta imagen","attachments":[{"type":"IMAGE","attachmentId":"%s"}]}
                                """.formatted(attachmentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.attachments[0].imageUrl", org.hamcrest.Matchers.matchesPattern(
                        "^/media/attachments/[0-9a-f-]{36}/original\\.jpg$")));
    }

    @Test
    void cannotReuseAnAttachmentAlreadyUsedByAnotherComment() throws Exception {
        UUID clipId = publishedClip("owner2-" + System.nanoTime() + "@example.com");
        String email = "uploader2-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);
        verifyEmail(email);
        String attachmentId = uploadImage(token);

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"primer uso","attachments":[{"type":"IMAGE","attachmentId":"%s"}]}
                                """.formatted(attachmentId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"segundo uso, deberia fallar","attachments":[{"type":"IMAGE","attachmentId":"%s"}]}
                                """.formatted(attachmentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ATTACHMENT_ALREADY_USED"));
    }

    @Test
    void cannotAttachAnotherUsersImage() throws Exception {
        UUID clipId = publishedClip("owner3-" + System.nanoTime() + "@example.com");
        String ownerEmail = "imgowner-" + System.nanoTime() + "@example.com";
        String ownerToken = registerAndLogin(ownerEmail);
        verifyEmail(ownerEmail);
        String attachmentId = uploadImage(ownerToken);

        String otherEmail = "thief-" + System.nanoTime() + "@example.com";
        String otherToken = registerAndLogin(otherEmail);

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType("application/json")
                        .content("""
                                {"body":"robando adjunto","attachments":[{"type":"IMAGE","attachmentId":"%s"}]}
                                """.formatted(attachmentId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_ATTACHMENT_OWNER"));
    }

    @Test
    void guestCommentWithAttachmentsIsRejected() throws Exception {
        UUID clipId = publishedClip("owner4-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments").contentType("application/json")
                        .content("""
                                {"body":"invitado con adjunto","turnstileToken":"dev-bypass",
                                 "attachments":[{"type":"CLIP_REFERENCE","referencedClipId":"%s"}]}
                                """.formatted(clipId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GUEST_ATTACHMENTS_NOT_ALLOWED"));
    }

    @Test
    void clipReferenceMustPointToAPublishedClip() throws Exception {
        UUID clipId = publishedClip("owner5-" + System.nanoTime() + "@example.com");
        String email = "referrer-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);

        // clip sin publicar (moderation_status = PENDING por default)
        User owner = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        Clip unpublished = clipRepository.save(new Clip(owner, ClipSourceType.OWN_UPLOAD));

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"referencio un clip no publicado","attachments":[{"type":"CLIP_REFERENCE","referencedClipId":"%s"}]}
                                """.formatted(unpublished.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("REFERENCED_CLIP_NOT_FOUND"));
    }

    @Test
    void clipReferenceToAPublishedClipSucceeds() throws Exception {
        UUID clipId = publishedClip("owner6-" + System.nanoTime() + "@example.com");
        UUID referencedClipId = publishedClip("owner7-" + System.nanoTime() + "@example.com");
        String email = "referrer2-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"mirá este otro clip","attachments":[{"type":"CLIP_REFERENCE","referencedClipId":"%s"}]}
                                """.formatted(referencedClipId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].type").value("CLIP_REFERENCE"))
                .andExpect(jsonPath("$.attachments[0].referencedClipId").value(referencedClipId.toString()));
    }

    @Test
    void linkToABlockedDomainForcesTheCommentToPendingReview() throws Exception {
        blockedLinkDomainRepository.save(new BlockedLinkDomain("malicious.example", "phishing conocido"));

        UUID clipId = publishedClip("owner8-" + System.nanoTime() + "@example.com");
        String email = "linker-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"mirá este link","attachments":[{"type":"LINK","linkUrl":"https://malicious.example/algo"}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/clips/" + clipId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void linkToAnAllowedDomainStaysVisible() throws Exception {
        UUID clipId = publishedClip("owner9-" + System.nanoTime() + "@example.com");
        String email = "linker2-" + System.nanoTime() + "@example.com";
        String token = registerAndLogin(email);

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"mirá este link confiable","attachments":[{"type":"LINK","linkUrl":"https://example.com/algo"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].linkDomain").value("example.com"));

        mockMvc.perform(get("/api/clips/" + clipId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].attachments[0].linkUrl").value("https://example.com/algo"));
    }
}
