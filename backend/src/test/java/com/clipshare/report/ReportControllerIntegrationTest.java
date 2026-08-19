package com.clipshare.report;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ClipSourceType;
import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cubre POST /api/reports (público) y POST /api/reports/{id}/counter-notice (dueño del clip)
 * — Fase 4, docs/SPEC.md secciones 2 y 8. La resolución de reportes por un admin es Fase 5.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ReportControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ClipRepository clipRepository;

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"supersecret1","displayName":"Reporter"}
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

    private UUID createClipOwnedBy(String ownerEmail) {
        User owner = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(ownerEmail).orElseThrow();
        Clip clip = new Clip(owner, ClipSourceType.OWN_UPLOAD);
        clip = clipRepository.save(clip);
        return clip.getId();
    }

    @Test
    void rejectsAnIncompleteDmcaNotice() throws Exception {
        String ownerEmail = "clipowner-" + System.nanoTime() + "@example.com";
        registerAndLogin(ownerEmail);
        UUID clipId = createClipOwnedBy(ownerEmail);

        mockMvc.perform(post("/api/reports").contentType("application/json")
                        .content("""
                                {"clipId":"%s","reason":"COPYRIGHT_DMCA","reporterEmail":"claimant@example.com"}
                                """.formatted(clipId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INCOMPLETE_DMCA_NOTICE"));
    }

    @Test
    void acceptsACompleteDmcaNoticeAndAllowsTheClipOwnerToCounterNotice() throws Exception {
        String ownerEmail = "clipowner-" + System.nanoTime() + "@example.com";
        String ownerToken = registerAndLogin(ownerEmail);
        UUID clipId = createClipOwnedBy(ownerEmail);

        String reportResponse = mockMvc.perform(post("/api/reports").contentType("application/json")
                        .content("""
                                {
                                  "clipId":"%s",
                                  "reason":"COPYRIGHT_DMCA",
                                  "reporterName":"Rights Holder",
                                  "reporterEmail":"claimant@example.com",
                                  "reporterAddress":"123 Main St, Springfield",
                                  "description":"Este clip usa mi video sin permiso",
                                  "goodFaithStatement":true,
                                  "accuracyStatement":true,
                                  "signature":"Rights Holder"
                                }
                                """.formatted(clipId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(objectMapper.readTree(reportResponse).get("id").asText());

        // otro usuario (no dueño del clip) no puede contra-notificar
        String strangerToken = registerAndLogin("stranger-" + System.nanoTime() + "@example.com");
        mockMvc.perform(post("/api/reports/" + reportId + "/counter-notice")
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType("application/json")
                        .content("""
                                {"statement":"No es mío","consentToJurisdiction":true,"signature":"Stranger"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_CLIP_OWNER"));

        // el dueño del clip sí puede
        String counterResponse = mockMvc.perform(post("/api/reports/" + reportId + "/counter-notice")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("""
                                {"statement":"Tengo licencia para usar este contenido","consentToJurisdiction":true,"signature":"Clip Owner"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Instant restoreEligibleAt = Instant.parse(objectMapper.readTree(counterResponse).get("restoreEligibleAt").asText());
        assertThat(restoreEligibleAt).isAfter(Instant.now());
    }

    @Test
    void nonDmcaReportsDoNotRequireTheFormalFields() throws Exception {
        String ownerEmail = "owner2-" + System.nanoTime() + "@example.com";
        registerAndLogin(ownerEmail);
        UUID clipId = createClipOwnedBy(ownerEmail);

        mockMvc.perform(post("/api/reports").contentType("application/json")
                        .content("""
                                {"clipId":"%s","reason":"HARASSMENT","reporterEmail":"witness@example.com","description":"Contenido de acoso"}
                                """.formatted(clipId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void reportingAMissingClipReturns404() throws Exception {
        mockMvc.perform(post("/api/reports").contentType("application/json")
                        .content("""
                                {"clipId":"%s","reason":"OTHER","reporterEmail":"someone@example.com"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CLIP_NOT_FOUND"));
    }
}
