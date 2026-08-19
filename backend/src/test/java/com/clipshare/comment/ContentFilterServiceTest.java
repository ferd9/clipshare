package com.clipshare.comment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentFilterServiceTest {

    @Mock
    CommentRepository commentRepository;

    @Mock
    ShadowBanService shadowBanService;

    ContentFilterService contentFilterService;

    @Test
    void forbiddenPatternAlwaysGoesToPendingReviewRegardlessOfTrust() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        CommentStatus status = contentFilterService.decideInitialStatus(
                CommentAuthorType.USER, true, "Hazte rico ya, mirá esto");
        assertThat(status).isEqualTo(CommentStatus.PENDING_REVIEW);
    }

    @Test
    void guestWithLinkGoesToPendingReview() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        CommentStatus status = contentFilterService.decideInitialStatus(
                CommentAuthorType.GUEST, false, "mirá esto https://example.com/algo");
        assertThat(status).isEqualTo(CommentStatus.PENDING_REVIEW);
    }

    @Test
    void untrustedUserWithLinkGoesToPendingReview() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        CommentStatus status = contentFilterService.decideInitialStatus(
                CommentAuthorType.USER, false, "mirá esto https://example.com/algo");
        assertThat(status).isEqualTo(CommentStatus.PENDING_REVIEW);
    }

    @Test
    void trustedUserWithLinkIsAllowedFreely() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        CommentStatus status = contentFilterService.decideInitialStatus(
                CommentAuthorType.USER, true, "mirá esto https://example.com/algo");
        assertThat(status).isEqualTo(CommentStatus.VISIBLE);
    }

    @Test
    void plainCommentWithoutSignalsIsVisible() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        CommentStatus status = contentFilterService.decideInitialStatus(
                CommentAuthorType.GUEST, false, "Buen clip!");
        assertThat(status).isEqualTo(CommentStatus.VISIBLE);
    }

    @Test
    void floodFromEnoughDistinctOriginsBansAllOfThem() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        when(commentRepository.findDistinctIpHashesWithContentHashSince(eq("hash"), any(Instant.class)))
                .thenReturn(List.of("ip1", "ip2", "ip3", "ip4"));

        boolean banned = contentFilterService.checkAndBanFloodOrigins("hash", "ip5");

        assertThat(banned).isTrue();
        verify(shadowBanService, times(5)).banIndefinitely(anyString(), eq(null), eq("duplicate_flood"));
    }

    @Test
    void belowThresholdDoesNotBanAnyone() {
        contentFilterService = new ContentFilterService(commentRepository, shadowBanService);
        when(commentRepository.findDistinctIpHashesWithContentHashSince(eq("hash"), any(Instant.class)))
                .thenReturn(List.of("ip1"));

        boolean banned = contentFilterService.checkAndBanFloodOrigins("hash", "ip2");

        assertThat(banned).isFalse();
        verify(shadowBanService, never()).banIndefinitely(anyString(), any(), anyString());
    }
}
