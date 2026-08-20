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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 6c (docs/SPEC.md sección 11.10). Usa URLs de Twitch a propósito en los casos que
 * ejercitan el pipeline completo vía HTTP — es la única plataforma sin llamada de red (el
 * iframe se arma directo del id, ver TwitchEmbedResolver), así que estos tests no dependen de
 * que YouTube/Vimeo/TikTok respondan desde este entorno.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class CommentEmbedIntegrationTest {

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
    void linkPreviewRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/link-preview").param("url", "https://www.twitch.tv/somechannel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void linkPreviewResolvesATwitchUrl() throws Exception {
        String token = registerAndLogin("previewer-" + System.nanoTime() + "@example.com");
        mockMvc.perform(get("/api/link-preview").param("url", "https://www.twitch.tv/somechannel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("TWITCH"))
                .andExpect(jsonPath("$.externalId").value("somechannel"))
                .andExpect(jsonPath("$.embeddable").value(true));
    }

    @Test
    void structuredLinkAttachmentToTwitchIsMarkedEmbeddable() throws Exception {
        UUID clipId = publishedClip("owner-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("embedder-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"mirá este stream","attachments":[{"type":"LINK","linkUrl":"https://www.twitch.tv/somechannel"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].embedPlatform").value("TWITCH"))
                .andExpect(jsonPath("$.attachments[0].embedExternalId").value("somechannel"))
                .andExpect(jsonPath("$.attachments[0].embeddable").value(true));
    }

    @Test
    void looseTwitchUrlInBodyIsAutoPromotedToALinkAttachment() throws Exception {
        UUID clipId = publishedClip("owner2-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("looselinker-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"dale una vuelta por https://www.twitch.tv/somechannel que está bueno"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].type").value("LINK"))
                .andExpect(jsonPath("$.attachments[0].embedPlatform").value("TWITCH"))
                .andExpect(jsonPath("$.attachments[0].embeddable").value(true));
    }

    @Test
    void looseUrlToAnUnrecognizedDomainIsNotPromoted() throws Exception {
        UUID clipId = publishedClip("owner3-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("plainlinker-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"mirá https://example.com/algo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments").isEmpty());
    }

    @Test
    void structuredLinkAttachmentToFacebookIsLabeledButNotEmbeddable() throws Exception {
        UUID clipId = publishedClip("owner4-" + System.nanoTime() + "@example.com");
        String token = registerAndLogin("fblinker-" + System.nanoTime() + "@example.com");

        mockMvc.perform(post("/api/clips/" + clipId + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"body":"video en facebook","attachments":[{"type":"LINK","linkUrl":"https://www.facebook.com/watch/?v=123456"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].embedPlatform").value("FACEBOOK"))
                .andExpect(jsonPath("$.attachments[0].embeddable").value(false));
    }
}
