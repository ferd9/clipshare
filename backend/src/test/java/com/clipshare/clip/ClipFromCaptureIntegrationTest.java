package com.clipshare.clip;

import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cubre POST /api/clips/from-capture (Fase 3, docs/SPEC.md sección 9 Caso B): el blob ya
 * viene recortado por el navegador (MediaRecorder), acá solo se valida la metadata y se
 * reutiliza el mismo pipeline de storage/cola que el upload propio (ver ClipService).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ClipFromCaptureIntegrationTest {

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

    private String registerAndVerifyUser() throws Exception {
        String email = "capture-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1","displayName":"Capturer"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginResponse).get("accessToken").asText();
    }

    @Test
    void capturesFromAnExternalLinkAndEnqueuesForProcessing() throws Exception {
        String accessToken = registerAndVerifyUser();
        MockMultipartFile blob = new MockMultipartFile("file", "capture.webm", "video/webm", "fake-webm-bytes".getBytes());

        String response = mockMvc.perform(multipart("/api/clips/from-capture").file(blob)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .param("sourcePlatform", "YOUTUBE")
                        .param("sourceExternalId", "dQw4w9WgXcQ")
                        .param("sourceClipStartMs", "5000")
                        .param("sourceClipEndMs", "15000")
                        .param("sourceTitle", "Un video de prueba"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();

        UUID clipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Clip clip = clipRepository.findById(clipId).orElseThrow();

        assertThat(clip.getSourceType()).isEqualTo(ClipSourceType.EXTERNAL_CAPTURE);
        assertThat(clip.getSourcePlatform()).isEqualTo(ClipPlatform.YOUTUBE);
        assertThat(clip.getSourceUrl()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(clip.getSourceExternalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(clip.getSourceClipStartMs()).isEqualTo(5000);
        assertThat(clip.getSourceClipEndMs()).isEqualTo(15000);
        assertThat(clip.getSourceTitle()).isEqualTo("Un video de prueba");
        assertThat(clip.getFilePath()).startsWith("raw/" + clipId + "/original");
        assertThat(storageDir.resolve(clip.getFilePath())).exists();
    }

    @Test
    void storesTheTrimRangeChosenInTheEditor() throws Exception {
        String accessToken = registerAndVerifyUser();
        MockMultipartFile blob = new MockMultipartFile("file", "capture.webm", "video/webm", "fake-webm-bytes".getBytes());

        String response = mockMvc.perform(multipart("/api/clips/from-capture").file(blob)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .param("sourcePlatform", "YOUTUBE")
                        .param("sourceClipStartMs", "5000")
                        .param("sourceClipEndMs", "15000")
                        .param("trimStartMs", "2000")
                        .param("trimEndMs", "12000"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        UUID clipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Clip clip = clipRepository.findById(clipId).orElseThrow();
        assertThat(clip.getTrimStartMs()).isEqualTo(2000);
        assertThat(clip.getTrimEndMs()).isEqualTo(12000);
    }

    @Test
    void omittingTrimParamsLeavesThemNullMeaningUseTheWholeRecording() throws Exception {
        String accessToken = registerAndVerifyUser();
        MockMultipartFile blob = new MockMultipartFile("file", "capture.webm", "video/webm", "bytes".getBytes());

        String response = mockMvc.perform(multipart("/api/clips/from-capture").file(blob)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .param("sourcePlatform", "YOUTUBE")
                        .param("sourceClipStartMs", "0")
                        .param("sourceClipEndMs", "8000"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        UUID clipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Clip clip = clipRepository.findById(clipId).orElseThrow();
        assertThat(clip.getTrimStartMs()).isEqualTo(0);
        assertThat(clip.getTrimEndMs()).isNull();
    }

    @Test
    void rejectsATrimRangeLongerThan20Seconds() throws Exception {
        String accessToken = registerAndVerifyUser();
        MockMultipartFile blob = new MockMultipartFile("file", "capture.webm", "video/webm", "bytes".getBytes());

        mockMvc.perform(multipart("/api/clips/from-capture").file(blob)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .param("sourcePlatform", "YOUTUBE")
                        .param("sourceClipStartMs", "0")
                        .param("sourceClipEndMs", "5000")
                        .param("trimStartMs", "0")
                        .param("trimEndMs", "25000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TRIM_RANGE"));
    }

    @Test
    void rejectsAPlatformOutsideTheSupportedThree() throws Exception {
        String accessToken = registerAndVerifyUser();
        MockMultipartFile blob = new MockMultipartFile("file", "capture.webm", "video/webm", "bytes".getBytes());

        mockMvc.perform(multipart("/api/clips/from-capture").file(blob)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://example.com/video")
                        .param("sourcePlatform", "NONE")
                        .param("sourceClipStartMs", "0")
                        .param("sourceClipEndMs", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLATFORM"));
    }

    @Test
    void rejectsAClipRangeLongerThan20Seconds() throws Exception {
        String accessToken = registerAndVerifyUser();
        MockMultipartFile blob = new MockMultipartFile("file", "capture.webm", "video/webm", "bytes".getBytes());

        mockMvc.perform(multipart("/api/clips/from-capture").file(blob)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://vimeo.com/12345")
                        .param("sourcePlatform", "VIMEO")
                        .param("sourceClipStartMs", "0")
                        .param("sourceClipEndMs", "25000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CLIP_RANGE"));
    }

    /** El límite de 3/día para cuentas sin verificar es un solo contador, no uno por endpoint. */
    @Test
    void dailyLimitForUnverifiedAccountsIsSharedAcrossBothUploadEndpoints() throws Exception {
        String email = "shared-limit-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1","displayName":"Shared"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(loginResponse).get("accessToken").asText();

        MockMultipartFile ownFile = new MockMultipartFile("file", "clip.mp4", "video/mp4", "bytes".getBytes());
        MockMultipartFile captureFile = new MockMultipartFile("file", "capture.webm", "video/webm", "bytes".getBytes());

        mockMvc.perform(multipart("/api/clips/upload").file(ownFile)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isAccepted());
        mockMvc.perform(multipart("/api/clips/from-capture").file(captureFile)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .param("sourcePlatform", "YOUTUBE")
                        .param("sourceClipStartMs", "0")
                        .param("sourceClipEndMs", "5000"))
                .andExpect(status().isAccepted());
        mockMvc.perform(multipart("/api/clips/upload").file(ownFile)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isAccepted());

        // van 3 (2 upload + 1 from-capture): la 4ta, sea cual sea el endpoint, se corta.
        mockMvc.perform(multipart("/api/clips/from-capture").file(captureFile)
                        .header("Authorization", "Bearer " + accessToken)
                        .param("sourceUrl", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                        .param("sourcePlatform", "YOUTUBE")
                        .param("sourceClipStartMs", "0")
                        .param("sourceClipEndMs", "5000"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("DAILY_CREATE_LIMIT_EXCEEDED"));
    }
}
