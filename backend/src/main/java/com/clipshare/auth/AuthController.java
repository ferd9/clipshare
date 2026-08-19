package com.clipshare.auth;

import com.clipshare.auth.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenHasher tokenHasher;

    public AuthController(AuthService authService, TokenHasher tokenHasher) {
        this.authService = authService;
        this.tokenHasher = tokenHasher;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
    }

    @PostMapping("/verify-email")
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, userAgentOf(httpRequest), ipHashOf(httpRequest));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        return authService.refresh(request, userAgentOf(httpRequest), ipHashOf(httpRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequestDto request) {
        authService.requestPasswordReset(request);
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmDto request) {
        authService.confirmPasswordReset(request);
    }

    private String userAgentOf(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    /** Nunca persistimos la IP en texto plano (minimización de datos), solo su hash. */
    private String ipHashOf(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = (forwardedFor != null && !forwardedFor.isBlank())
                ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();
        return tokenHasher.sha256Hex(ip);
    }
}
