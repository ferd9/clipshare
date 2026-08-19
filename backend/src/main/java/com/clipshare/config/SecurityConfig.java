package com.clipshare.config;

import com.clipshare.auth.JwtAuthenticationFilter;
import com.clipshare.auth.JwtService;
import com.clipshare.user.UserRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * API stateless: sin sesión HTTP ni CSRF (no hay cookies de sesión para endpoints
 * autenticados), autenticación vía JWT en el header Authorization (ver docs/SPEC.md sección 12).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new org.springframework.security.authentication.ProviderManager(provider);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        return new JwtAuthenticationFilter(jwtService, userRepository);
    }

    /**
     * Cadena aparte para /media/clips/** (video/thumbnail servidos por WebConfig), con más
     * prioridad (@Order menor) que la cadena principal. La necesitamos separada porque Spring
     * Security agrega por defecto {@code Cache-Control: no-store} a toda respuesta — y Chrome
     * directamente se cuelga cargando un <video> cuando el recurso llega marcado como no
     * cacheable (el <video> se queda en HAVE_NOTHING para siempre, sin emitir ningún error).
     * Acá lo desactivamos y dejamos que el Cache-Control público lo ponga el resource handler
     * de Spring MVC (ver WebConfig), que es el lugar correcto para cachear contenido estático.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mediaSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/media/clips/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.cacheControl(cache -> cache.disable()));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/legal/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/clips/feed", "/api/clips/{id}").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/reports").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Sin esto, Spring Security devuelve 403 tanto para "sin credenciales" como para
                // "credenciales insuficientes"; separamos 401/403 y usamos el mismo formato de
                // error {error, message} que el resto de la API (sección 8 del spec).
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            // Content-Type con charset UTF-8 explícito + escritura a bytes (no al Writer,
                            // que por defecto en Servlet cae a ISO-8859-1 y rompe tildes/ñ).
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                            objectMapper.writeValue(response.getOutputStream(),
                                    new ErrorResponse("UNAUTHENTICATED", "Se requiere iniciar sesión"));
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                            objectMapper.writeValue(response.getOutputStream(),
                                    new ErrorResponse("FORBIDDEN", "No tenés permiso para esta acción"));
                        }));

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // En dev, el frontend (Vite) corre en otro origen; en prod se restringe al dominio real.
        configuration.setAllowedOrigins(List.of("http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
