package com.clipshare.moderation;

import com.clipshare.auth.RefreshToken;
import com.clipshare.auth.RefreshTokenRepository;
import com.clipshare.user.User;
import com.clipshare.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrikeServiceTest {

    @Mock
    StrikeRepository strikeRepository;

    @Mock
    RefreshTokenRepository refreshTokenRepository;

    StrikeService strikeService;

    private User userWithId(UUID id) throws Exception {
        User user = new User("user@example.com", "hash", "Nombre");
        Field idField = User.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, id);
        return user;
    }

    @Test
    void firstAndSecondStandardStrikesDoNotSuspend() throws Exception {
        strikeService = new StrikeService(strikeRepository, refreshTokenRepository);
        User user = userWithId(UUID.randomUUID());

        when(strikeRepository.countActiveStandardStrikes(eq(user.getId()), any())).thenReturn(1L);
        strikeService.recordStandardStrike(user, StrikeReason.HARASSMENT, null);
        assertThat(user.getStatus()).isNotEqualTo(UserStatus.SUSPENDED);

        when(strikeRepository.countActiveStandardStrikes(eq(user.getId()), any())).thenReturn(2L);
        strikeService.recordStandardStrike(user, StrikeReason.HARASSMENT, null);
        assertThat(user.getStatus()).isNotEqualTo(UserStatus.SUSPENDED);

        verify(refreshTokenRepository, never()).findAllByUserIdAndRevokedAtIsNull(any());
    }

    @Test
    void thirdActiveStandardStrikeSuspendsAndRevokesSessions() throws Exception {
        strikeService = new StrikeService(strikeRepository, refreshTokenRepository);
        User user = userWithId(UUID.randomUUID());
        RefreshToken activeToken = mock(RefreshToken.class);
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).thenReturn(List.of(activeToken));
        when(strikeRepository.countActiveStandardStrikes(eq(user.getId()), any())).thenReturn(3L);

        strikeService.recordStandardStrike(user, StrikeReason.DMCA_CONFIRMED, null);

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(activeToken).revoke();
        verify(strikeRepository).save(any(Strike.class));
    }

    @Test
    void csamStrikeBansImmediatelyWithoutWaitingForACount() throws Exception {
        strikeService = new StrikeService(strikeRepository, refreshTokenRepository);
        User user = userWithId(UUID.randomUUID());
        RefreshToken activeToken = mock(RefreshToken.class);
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).thenReturn(List.of(activeToken));

        strikeService.recordCsamStrike(user, null);

        assertThat(user.getStatus()).isEqualTo(UserStatus.BANNED);
        verify(activeToken).revoke();
        verify(strikeRepository).save(any(Strike.class));
        verify(strikeRepository, never()).countActiveStandardStrikes(any(), any());
    }
}
