package com.clipshare.report;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ClipSourceType;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import com.clipshare.user.UserRole;
import com.clipshare.user.UserStatus;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cola de admin para resolver reportes (Fase 5, docs/SPEC.md sección 14). Protegido por rol
 * — /api/admin/** exige ADMIN o MODERATOR (ver SecurityConfig).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class AdminReportControllerIntegrationTest {

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

    @Autowired
    ReportRepository reportRepository;

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

    private String promoteToAdminAndLogin(String email) throws Exception {
        String token = registerAndLogin(email);
        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
        return token;
    }

    private UUID createReportedClip(String ownerEmail, ReportReason reason) {
        User owner = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(ownerEmail).orElseThrow();
        Clip clip = clipRepository.save(new Clip(owner, ClipSourceType.OWN_UPLOAD));
        Report report = new Report(clip, reason, "Reporter", "reporter@example.com",
                "123 Main St", "desc", true, true, "Reporter");
        reportRepository.save(report);
        return report.getId();
    }

    @Test
    void nonAdminCannotListPendingReports() throws Exception {
        String token = registerAndLogin("regular-" + System.nanoTime() + "@example.com");
        mockMvc.perform(get("/api/admin/reports").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListAndDismissAReport() throws Exception {
        String adminToken = promoteToAdminAndLogin("admin-" + System.nanoTime() + "@example.com");
        String ownerEmail = "owner-" + System.nanoTime() + "@example.com";
        registerAndLogin(ownerEmail);
        UUID reportId = createReportedClip(ownerEmail, ReportReason.OTHER);

        mockMvc.perform(get("/api/admin/reports").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + reportId + "')]").exists());

        mockMvc.perform(post("/api/admin/reports/" + reportId + "/action")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"action":"DISMISSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        // ya resuelto: una segunda acción se rechaza
        mockMvc.perform(post("/api/admin/reports/" + reportId + "/action")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"action":"CONFIRMED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("REPORT_ALREADY_RESOLVED"));
    }

    @Test
    void confirmingADmcaReportTakesDownTheClipAndStrikesTheOwner() throws Exception {
        String adminToken = promoteToAdminAndLogin("admin2-" + System.nanoTime() + "@example.com");
        String ownerEmail = "owner2-" + System.nanoTime() + "@example.com";
        registerAndLogin(ownerEmail);
        UUID reportId = createReportedClip(ownerEmail, ReportReason.COPYRIGHT_DMCA);
        UUID clipId = reportRepository.findById(reportId).orElseThrow().getClip().getId();

        mockMvc.perform(post("/api/admin/reports/" + reportId + "/action")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"action":"CONFIRMED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"));

        Clip clip = clipRepository.findById(clipId).orElseThrow();
        assertThat(clip.getModerationStatus()).isEqualTo(ModerationStatus.TAKEN_DOWN);
    }

    @Test
    void confirmingACsamReportBansTheOwnerImmediately() throws Exception {
        String adminToken = promoteToAdminAndLogin("admin3-" + System.nanoTime() + "@example.com");
        String ownerEmail = "owner3-" + System.nanoTime() + "@example.com";
        registerAndLogin(ownerEmail);
        UUID reportId = createReportedClip(ownerEmail, ReportReason.CSAM);

        mockMvc.perform(post("/api/admin/reports/" + reportId + "/action")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("""
                                {"action":"CONFIRMED"}
                                """))
                .andExpect(status().isOk());

        User owner = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(ownerEmail).orElseThrow();
        assertThat(owner.getStatus()).isEqualTo(UserStatus.BANNED);
    }
}
