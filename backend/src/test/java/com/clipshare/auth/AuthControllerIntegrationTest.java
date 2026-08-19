package com.clipshare.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contra un Postgres real (Testcontainers), con las migraciones Flyway aplicadas tal
 * cual correrían en dev/prod: los tipos ENUM nativos de V1__users_auth.sql no existen
 * en una base en memoria, así que un mock/H2 no sería representativo acá.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void registerLoginMeAndRefreshRotationFlow() throws Exception {
        String email = "flow-" + System.nanoTime() + "@example.com";
        String registerBody = objectMapper.writeValueAsString(new RegisterPayload(email, "supersecret1", "Flow User"));

        mockMvc.perform(post("/api/auth/register").contentType("application/json").content(registerBody))
                .andExpect(status().isCreated());

        // Email duplicado -> 409, formato de error consistente (sección 8 del spec)
        mockMvc.perform(post("/api/auth/register").contentType("application/json").content(registerBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_TAKEN"));

        String loginBody = objectMapper.writeValueAsString(new LoginPayload(email, "supersecret1"));
        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode tokens = objectMapper.readTree(loginResponse);
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(false));

        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());

        String refreshBody = objectMapper.writeValueAsString(new RefreshPayload(refreshToken));
        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // Rotación: el refresh token ya usado queda revocado y no sirve una segunda vez.
        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void loginWithWrongPasswordReturns401WithConsistentErrorFormat() throws Exception {
        String email = "wrongpw-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RegisterPayload(email, "supersecret1", "User"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginPayload(email, "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String email = "logout-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RegisterPayload(email, "supersecret1", "User"))))
                .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginPayload(email, "supersecret1"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(loginResponse).get("refreshToken").asText();
        String refreshBody = objectMapper.writeValueAsString(new RefreshPayload(refreshToken));

        mockMvc.perform(post("/api/auth/logout").contentType("application/json").content(refreshBody))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh").contentType("application/json").content(refreshBody))
                .andExpect(status().isUnauthorized());
    }

    record RegisterPayload(String email, String password, String displayName) {
    }

    record LoginPayload(String email, String password) {
    }

    record RefreshPayload(String refreshToken) {
    }
}
