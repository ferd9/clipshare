package com.clipshare.clip;

import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import com.clipshare.worker.YtDlpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cubre POST /api/clips/import y el editor de recorte (docs/SPEC.md, pivote a import
 * server-side): a diferencia de ClipUploadIntegrationTest, acá {@link YtDlpClient} se
 * reemplaza por un mock — ni el pre-chequeo de metadata ni la descarga real deben depender de
 * red/yt-dlp instalado para que este test corra en cualquier lado. La descarga/transcodeo en
 * sí (fase STAGE) la hace el worker (@Profile("worker"), fuera de este contexto) y se verificó
 * manualmente contra el stack de Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ClipImportIntegrationTest {

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

    @Autowired
    StringRedisTemplate redisTemplate;

    @MockitoBean
    YtDlpClient ytDlpClient;

    // El contenedor de Redis es @Container static (compartido entre todos los métodos de la
    // clase, arranca una sola vez) — sin esto, un job encolado por un test y nunca
    // desencolado (ej. dailyLimitIsSharedAcrossUploadAndImportEndpoints) queda flotando en
    // la cola y contamina el rightPop() de otro test que corre después.
    @BeforeEach
    void clearQueue() {
        redisTemplate.delete(ClipQueuePublisher.QUEUE_KEY);
    }

    @Test
    void importRequiresAuthFetchesMetadataAndEnqueuesForStaging() throws Exception {
        when(ytDlpClient.fetchMetadata(anyString())).thenReturn(new YtDlpClient.Metadata(180_000L, "Un video de prueba"));
        String accessToken = registerAndVerifyUser("importer");

        mockMvc.perform(post("/api/clips/import"))
                .andExpect(status().isUnauthorized());

        String response = mockMvc.perform(post("/api/clips/import")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("""
                                {"sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","sourcePlatform":"YOUTUBE"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();

        UUID clipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        Clip clip = clipRepository.findById(clipId).orElseThrow();
        assertThat(clip.getSourceType()).isEqualTo(ClipSourceType.EXTERNAL_CAPTURE);
        assertThat(clip.getSourcePlatform()).isEqualTo(ClipPlatform.YOUTUBE);
        assertThat(clip.getSourceUrl()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(clip.getSourceTitle()).isEqualTo("Un video de prueba");
        assertThat(clip.getFilePath()).isNull(); // recién lo setea el worker al descargar (fase STAGE)

        // El job encolado es de tipo STAGE, no FINALIZE.
        String queued = redisTemplate.opsForList().rightPop(ClipQueuePublisher.QUEUE_KEY);
        assertThat(queued).isNotNull();
        JsonNode job = objectMapper.readTree(queued);
        assertThat(job.get("clipId").asText()).isEqualTo(clipId.toString());
        assertThat(job.get("jobType").asText()).isEqualTo("STAGE");
    }

    @Test
    void rejectsAPlatformOutsideTheSupportedThree() throws Exception {
        String accessToken = registerAndVerifyUser("badplatform");
        mockMvc.perform(post("/api/clips/import")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("""
                                {"sourceUrl":"https://example.com/video","sourcePlatform":"NONE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLATFORM"));
    }

    @Test
    void rejectsASourceLongerThanTenMinutes() throws Exception {
        when(ytDlpClient.fetchMetadata(anyString())).thenReturn(new YtDlpClient.Metadata(700_000L, "Video largo"));
        String accessToken = registerAndVerifyUser("toolong");

        mockMvc.perform(post("/api/clips/import")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("""
                                {"sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","sourcePlatform":"YOUTUBE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SOURCE_TOO_LONG"));
    }

    /** Instagram (reels con entrega DASH, confirmado probando en vivo) puede no exponer
     * duración en la metadata que devuelve yt-dlp aunque el video sea normal, no una
     * transmisión en vivo — no debe bloquear el import (la duración real se valida igual
     * después de descargar, ver ClipProcessingWorker.stageClip). Antes de este fix,
     * durationMs=null ni siquiera era representable (el record usaba long primitivo). */
    @Test
    void acceptsASourceWithUnknownDuration() throws Exception {
        when(ytDlpClient.fetchMetadata(anyString())).thenReturn(new YtDlpClient.Metadata(null, "Reel sin duración expuesta"));
        String accessToken = registerAndVerifyUser("unknownduration");

        mockMvc.perform(post("/api/clips/import")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json")
                        .content("""
                                {"sourceUrl":"https://www.instagram.com/reel/DaECrljBy5H/","sourcePlatform":"INSTAGRAM"}
                                """))
                .andExpect(status().isAccepted());
    }

    /** El límite de 3/día para cuentas sin verificar es un solo contador, no uno por endpoint. */
    @Test
    void dailyLimitIsSharedAcrossUploadAndImportEndpoints() throws Exception {
        when(ytDlpClient.fetchMetadata(anyString())).thenReturn(new YtDlpClient.Metadata(60_000L, "Corto"));
        String email = "shared-limit-" + System.nanoTime() + "@example.com";
        register(email);
        String accessToken = login(email);

        MockMultipartFile ownFile = new MockMultipartFile("file", "clip.mp4", "video/mp4", "bytes".getBytes());
        String importBody = """
                {"sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","sourcePlatform":"YOUTUBE"}
                """;

        mockMvc.perform(multipart("/api/clips/upload").file(ownFile).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/clips/import").header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json").content(importBody))
                .andExpect(status().isAccepted());
        mockMvc.perform(multipart("/api/clips/upload").file(ownFile).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isAccepted());

        // van 3 (2 upload + 1 import): la 4ta, sea cual sea el endpoint, se corta.
        mockMvc.perform(post("/api/clips/import").header("Authorization", "Bearer " + accessToken)
                        .contentType("application/json").content(importBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("DAILY_CREATE_LIMIT_EXCEEDED"));
    }

    @Test
    void editableFileIsOnlyServedToTheOwnerWhileAwaitingEdit() throws Exception {
        String ownerToken = registerAndVerifyUser("owner");
        String strangerToken = registerAndVerifyUser("stranger");
        UUID clipId = createClipAwaitingEdit(ownerToken, "fake-editable-mp4-bytes");

        mockMvc.perform(get("/api/clips/" + clipId + "/editable"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/clips/" + clipId + "/editable").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/clips/" + clipId + "/editable").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void finalizeValidatesTrimRangeThenEnqueuesFinalizeJob() throws Exception {
        String ownerToken = registerAndVerifyUser("finalizer");
        UUID clipId = createClipAwaitingEdit(ownerToken, "fake-editable-mp4-bytes");

        // rango mayor al máximo (ClipService.MAX_CLIP_DURATION_MS = 40s) -> rechazado, el
        // clip sigue en AWAITING_EDIT
        mockMvc.perform(post("/api/clips/" + clipId + "/finalize")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("""
                                {"trimStartMs":0,"trimEndMs":45000,"muteOriginalAudio":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TRIM_RANGE"));

        mockMvc.perform(post("/api/clips/" + clipId + "/finalize")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("""
                                {"trimStartMs":1000,"trimEndMs":9000,"muteOriginalAudio":true}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processingStatus").value("PROCESSING"));

        Clip clip = clipRepository.findById(clipId).orElseThrow();
        assertThat(clip.getTrimStartMs()).isEqualTo(1000);
        assertThat(clip.getTrimEndMs()).isEqualTo(9000);
        assertThat(clip.isMuteOriginalAudio()).isTrue();

        String queued = redisTemplate.opsForList().rightPop(ClipQueuePublisher.QUEUE_KEY);
        assertThat(queued).isNotNull();
        JsonNode job = objectMapper.readTree(queued);
        assertThat(job.get("jobType").asText()).isEqualTo("FINALIZE");
    }

    /** Simula lo que deja hecho el worker al terminar la fase STAGE (sin correr ffmpeg de
     * verdad): un clip AWAITING_EDIT con un archivo "editable" real en disco. */
    private UUID createClipAwaitingEdit(String ownerToken, String fakeVideoContent) throws Exception {
        when(ytDlpClient.fetchMetadata(anyString())).thenReturn(new YtDlpClient.Metadata(60_000L, "Video"));
        String response = mockMvc.perform(post("/api/clips/import")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("""
                                {"sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","sourcePlatform":"YOUTUBE"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID clipId = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        redisTemplate.opsForList().rightPop(ClipQueuePublisher.QUEUE_KEY); // limpiar el job STAGE encolado, no interesa acá

        Clip clip = clipRepository.findById(clipId).orElseThrow();
        String editableRelativePath = "work/" + clipId + "/editable.mp4";
        Path editablePath = storageDir.resolve(editableRelativePath);
        Files.createDirectories(editablePath.getParent());
        Files.writeString(editablePath, fakeVideoContent);
        clip.setFilePath(editableRelativePath);
        clip.setProcessingStatus(ProcessingStatus.AWAITING_EDIT);
        clipRepository.save(clip);
        return clipId;
    }

    private String registerAndVerifyUser(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        register(email);
        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        return login(email);
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1","displayName":"Tester"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginResponse).get("accessToken").asText();
    }
}
