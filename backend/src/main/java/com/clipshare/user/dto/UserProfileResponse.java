package com.clipshare.user.dto;

import com.clipshare.user.User;
import com.clipshare.user.UserRole;
import com.clipshare.user.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        Instant createdAt
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt());
    }
}
