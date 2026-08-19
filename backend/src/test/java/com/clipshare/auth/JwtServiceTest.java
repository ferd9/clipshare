package com.clipshare.auth;

import com.clipshare.user.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private User userWithId(UUID id) throws Exception {
        User user = new User("user@example.com", "hash", "Nombre");
        Field idField = User.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, id);
        return user;
    }

    @Test
    void generatesAndParsesAValidToken() throws Exception {
        JwtService jwtService = new JwtService("a-short-dev-secret", 20);
        UUID userId = UUID.randomUUID();
        User user = userWithId(userId);

        String token = jwtService.generateAccessToken(user);
        var claims = jwtService.parseAndValidate(token);

        assertThat(jwtService.extractUserId(claims)).isEqualTo(userId);
        assertThat(claims.get("email")).isEqualTo("user@example.com");
        assertThat(claims.get("role")).isEqualTo("USER");
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        // TTL de 0 minutos: el token queda vencido apenas se genera.
        JwtService jwtService = new JwtService("a-short-dev-secret", 0);
        String token = jwtService.generateAccessToken(userWithId(UUID.randomUUID()));

        assertThatThrownBy(() -> jwtService.parseAndValidate(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsATamperedToken() throws Exception {
        JwtService jwtService = new JwtService("a-short-dev-secret", 20);
        String token = jwtService.generateAccessToken(userWithId(UUID.randomUUID()));
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtService.parseAndValidate(tampered))
                .isInstanceOf(JwtException.class);
    }
}
