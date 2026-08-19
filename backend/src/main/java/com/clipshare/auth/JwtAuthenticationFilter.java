package com.clipshare.auth;

import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Autentica cada request vía JWT en el header Authorization. Recarga el usuario desde la
 * base de datos (en vez de confiar ciegamente en los claims del token) para que una
 * suspensión/ban tome efecto de inmediato, sin esperar a que expire el access token.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring("Bearer ".length());
        try {
            Claims claims = jwtService.parseAndValidate(token);
            UUID userId = jwtService.extractUserId(claims);

            Optional<User> user = userRepository.findById(userId)
                    .filter(u -> !u.isDeleted());

            if (user.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                AppUserPrincipal principal = new AppUserPrincipal(user.get());
                // isEnabled()/isAccountNonLocked() solo los chequea Spring Security en el login
                // interactivo (DaoAuthenticationProvider) — acá hay que pedirlos a mano, porque
                // un JWT ya emitido no pasa por ese camino. Sin esto, un strike/ban (StrikeService)
                // solo tendría efecto recién cuando el access token ya vigente expirase solo.
                if (principal.isEnabled() && principal.isAccountNonLocked()) {
                    var authToken = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // Token inválido/expirado: seguimos sin autenticar: el endpoint decide si eso alcanza (401/403).
        }

        filterChain.doFilter(request, response);
    }
}
