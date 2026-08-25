package com.clipshare.user;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.auth.AuthService;
import com.clipshare.user.dto.UserProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public UserProfileResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return UserProfileResponse.from(principal.getUser());
    }

    // Bajo /api/users (no /api/auth) a propósito: "/api/auth/**" es todo permitAll en
    // SecurityConfig (necesario para login/registro sin sesión) — esta acción sí necesita
    // saber DE QUIÉN es la cuenta a reenviar, así que requiere estar autenticado.
    @PostMapping("/me/resend-verification-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerificationEmail(@AuthenticationPrincipal AppUserPrincipal principal) {
        authService.resendVerificationEmail(principal.getUser());
    }
}
