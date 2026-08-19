package com.clipshare.clip;

import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cubre POST /api/clips/upload de punta a punta (requisito de la Fase 2, docs/SPEC.md
 * sección 14) contra un Postgres real. El worker (@Profile("worker")) no forma parte de
 * este contexto, así que solo se ejercita la mitad "API" del pipeline: creación del clip,
 * guardado del archivo crudo y encolado — no el procesamiento con ffmpeg en sí (eso se
 * verificó manualmente contra el stack de Docker, que sí tiene ffmpeg instalado).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ClipUploadIntegrationTest {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ClipRepository clipRepository;

    @Test
    void uploadRequiresAuthAndVerifiedEmailThenEnqueuesForProcessing() throws Exception {
        String email = "uploader-" + System.nanoTime() + "@example.com";

        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1","displayName":"Uploader"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(loginResponse).get("accessToken").asText();

        MockMultipartFile videoFile = new MockMultipartFile("file", "clip.mp4", "video/mp4", "fake-bytes".getBytes());

        // sin token -> 401
        mockMvc.perform(multipart("/api/clips/upload").file(videoFile))
                .andExpect(status().isUnauthorized());

        // con token pero email sin verificar -> 403
        mockMvc.perform(multipart("/api/clips/upload").file(videoFile)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));

        verifyEmail(email);

        String uploadResponse = mockMvc.perform(multipart("/api/clips/upload").file(videoFile)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(uploadResponse);
        UUID clipId = UUID.fromString(body.get("id").asText());

        Clip clip = clipRepository.findById(clipId).orElseThrow();
        assertThat(clip.getSourceType()).isEqualTo(ClipSourceType.OWN_UPLOAD);
        assertThat(clip.getProcessingStatus()).isEqualTo(ProcessingStatus.QUEUED);
        assertThat(clip.getModerationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(clip.getFilePath()).startsWith("raw/" + clipId + "/original");
        assertThat(storageDir.resolve(clip.getFilePath())).exists();

        // Pendiente de moderación: nadie más que el dueño lo puede ver todavía (sección 10 del spec).
        mockMvc.perform(get("/api/clips/" + clipId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/clips/" + clipId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"))
                .andExpect(jsonPath("$.videoUrl").doesNotExist());

        // No aparece en el feed público hasta que el worker lo publique.
        mockMvc.perform(get("/api/clips/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private void verifyEmail(String email) {
        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
    }
}
