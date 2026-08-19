package com.clipshare.comment;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ClipSourceType;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 6 (docs/SPEC.md sección 11): comentarios abiertos a cualquiera, con controles más
 * estrictos para invitados (CAPTCHA obligatorio, ver MockTurnstileClient) y filtro de
 * contenido/umbral de reportes que mandan un comentario a revisión.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CommentControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ClipRepository clipRepository;
    @Autowired CommentRepository commentRepository;

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

    private UUID publishedClip(String ownerEmail) throws Exception {
        registerAndLogin(ownerEmail);
        User owner = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(ownerEmail).orElseThrow();
        Clip clip = new Clip(owner, ClipSourceType.OWN_UPLOAD);
        clip.setModerationStatus(ModerationStatus.PUBLISHED);
        clipRepository.save(clip);
        return clip.getId();
    }

    @Test
    void guestCommentWithoutTurnstileTokenIsRejected() throws Exception {
        UUID clipId = publishedClip("owner-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments").contentType("application/json")
                        .content("""
                                {"body":"Buen clip!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TURNSTILE_REQUIRED"));
    }

    @Test
    void guestCommentWithTurnstileTokenIsCreatedWithGeneratedDisplayName() throws Exception {
        UUID clipId = publishedClip("owner2-" + System.nanoTime() + "@example.com");

        String response = mockMvc.perform(post("/api/clips/" + clipId + "/comments").contentType("application/json")
                        .content("""
                                {"body":"Buen clip!","turnstileToken":"dev-bypass"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("GUEST"))
                .andReturn().getResponse().getContentAsString();

        String authorName = objectMapper.readTree(response).get("authorDisplayName").asText();
        assertThat(authorName).startsWith("Invitado #");

        mockMvc.perform(get("/api/clips/" + clipId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].authorDisplayName").value(authorName));
    }

    @Test
    void authenticatedUserCanCommentWithoutTurnstile() throws Exception {
        UUID clipId = publishedClip("owner3-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("commenter-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"Comentario de usuario con cuenta"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorType").value("USER"));
    }

    @Test
    void commentMatchingForbiddenPatternIsHiddenFromThePublicList() throws Exception {
        UUID clipId = publishedClip("owner4-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("spammer-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"Hazte rico ya mismo, escribime"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/clips/" + clipId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void enoughReportsFlipsACommentToPendingReviewAndHidesItFromTheList() throws Exception {
        UUID clipId = publishedClip("owner5-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("author-" + System.nanoTime() + "@example.com");

        String createResponse = mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"Comentario normal"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID commentId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/comments/" + commentId + "/report").contentType("application/json")
                            .content("""
                                    {"reason":"HARASSMENT","reporterEmail":"reporter%d@example.com"}
                                    """.formatted(i)))
                    .andExpect(status().isCreated());
        }

        Comment comment = commentRepository.findById(commentId).orElseThrow();
        assertThat(comment.getStatus()).isEqualTo(CommentStatus.PENDING_REVIEW);

        mockMvc.perform(get("/api/clips/" + clipId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + commentId + "')]").doesNotExist());
    }

    @Test
    void onlyTheOwnerOrAModeratorCanDeleteAComment() throws Exception {
        UUID clipId = publishedClip("owner6-" + System.nanoTime() + "@example.com");
        String ownerToken = registerAndLogin("commentowner-" + System.nanoTime() + "@example.com");
        String otherToken = registerAndLogin("someoneelse-" + System.nanoTime() + "@example.com");

        String createResponse = mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("""
                                {"body":"Mi comentario"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID commentId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        mockMvc.perform(delete("/api/comments/" + commentId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/comments/" + commentId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/clips/" + clipId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
