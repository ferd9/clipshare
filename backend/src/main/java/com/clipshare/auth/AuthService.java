package com.clipshare.auth;

import com.clipshare.auth.dto.*;
import com.clipshare.config.ApiException;
import com.clipshare.user.User;
import com.clipshare.user.UserRepository;
import com.clipshare.user.UserStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;
    private final EmailService emailService;

    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Duration emailVerificationTtl;
    private final Duration passwordResetTtl;

    public AuthService(
            UserRepository userRepository,
            EmailVerificationTokenRepository emailVerificationTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            TokenHasher tokenHasher,
            EmailService emailService,
            @Value("${app.jwt.access-token-ttl-minutes:20}") long accessTokenTtlMinutes,
            @Value("${app.jwt.refresh-token-ttl-days:30}") long refreshTokenTtlDays,
            @Value("${app.auth.email-verification-ttl-hours:24}") long emailVerificationTtlHours,
            @Value("${app.auth.password-reset-ttl-hours:1}") long passwordResetTtlHours) {
        this.userRepository = userRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.tokenHasher = tokenHasher;
        this.emailService = emailService;
        this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
        this.refreshTokenTtl = Duration.ofDays(refreshTokenTtlDays);
        this.emailVerificationTtl = Duration.ofHours(emailVerificationTtlHours);
        this.passwordResetTtl = Duration.ofHours(passwordResetTtlHours);
    }

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(request.email())) {
            throw ApiException.conflict("EMAIL_TAKEN", "Ya existe una cuenta con ese email");
        }

        User user = new User(
                request.email().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.displayName());
        userRepository.save(user);

        issueEmailVerificationToken(user);
    }

    /** Para una cuenta que ya existe pero todavía no verificó el email (docs/SPEC.md sección
     * 12) — el registro original ya le mandó un token, pero puede vencer, perderse, o (caso
     * real que motivó esto) que el usuario haya creado contenido con esa cuenta y no pueda
     * simplemente registrarse de nuevo con el mismo email para conseguir un token nuevo. */
    @Transactional
    public void resendVerificationEmail(User user) {
        if (user.isEmailVerified()) {
            throw ApiException.badRequest("ALREADY_VERIFIED", "Esta cuenta ya está verificada");
        }
        issueEmailVerificationToken(user);
    }

    private void issueEmailVerificationToken(User user) {
        String opaqueToken = tokenHasher.generateOpaqueToken();
        EmailVerificationToken token = new EmailVerificationToken(
                user, tokenHasher.sha256Hex(opaqueToken), Instant.now().plus(emailVerificationTtl));
        emailVerificationTokenRepository.save(token);
        emailService.sendVerificationEmail(user.getEmail(), opaqueToken);
    }

    @Transactional
    public void verifyEmail(VerifyEmailRequest request) {
        EmailVerificationToken token = emailVerificationTokenRepository
                .findByTokenHash(tokenHasher.sha256Hex(request.token()))
                .filter(EmailVerificationToken::isUsable)
                .orElseThrow(() -> ApiException.badRequest("INVALID_TOKEN", "Token de verificación inválido o expirado"));

        token.markUsed();

        User user = token.getUser();
        user.setEmailVerifiedAt(Instant.now());
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent, String ipHash) {
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().toLowerCase(), request.password()));

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return issueTokenPair(principal.getUser(), userAgent, ipHash);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request, String userAgent, String ipHash) {
        RefreshToken existing = refreshTokenRepository
                .findByTokenHash(tokenHasher.sha256Hex(request.refreshToken()))
                .filter(RefreshToken::isUsable)
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token inválido o expirado"));

        User user = existing.getUser();
        if (user.isDeleted() || user.getStatus() == UserStatus.BANNED || user.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("ACCOUNT_BLOCKED", "La cuenta no está activa");
        }

        existing.revoke(); // rotación: cada refresh invalida el token anterior
        return issueTokenPair(user, userAgent, ipHash);
    }

    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByTokenHash(tokenHasher.sha256Hex(request.refreshToken()))
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional
    public void requestPasswordReset(PasswordResetRequestDto request) {
        // Nunca revelar si el email existe o no (evita enumeración de cuentas): siempre "éxito" desde afuera.
        userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(request.email()).ifPresent(user -> {
            String opaqueToken = tokenHasher.generateOpaqueToken();
            PasswordResetToken token = new PasswordResetToken(
                    user, tokenHasher.sha256Hex(opaqueToken), Instant.now().plus(passwordResetTtl));
            passwordResetTokenRepository.save(token);
            emailService.sendPasswordResetEmail(user.getEmail(), opaqueToken);
        });
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmDto request) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(tokenHasher.sha256Hex(request.token()))
                .filter(PasswordResetToken::isUsable)
                .orElseThrow(() -> ApiException.badRequest("INVALID_TOKEN", "Token de reset inválido o expirado"));

        token.markUsed();

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));

        // Cambiar la contraseña cierra todas las sesiones activas, no solo la que la pidió.
        refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())
                .forEach(RefreshToken::revoke);
    }

    private AuthResponse issueTokenPair(User user, String userAgent, String ipHash) {
        String accessToken = jwtService.generateAccessToken(user);

        String opaqueRefreshToken = tokenHasher.generateOpaqueToken();
        RefreshToken refreshToken = new RefreshToken(
                user, tokenHasher.sha256Hex(opaqueRefreshToken), userAgent, ipHash,
                Instant.now().plus(refreshTokenTtl));
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.of(accessToken, opaqueRefreshToken, accessTokenTtl.toSeconds());
    }
}
