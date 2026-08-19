package com.clipshare.moderation;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ClipSourceType;
import com.clipshare.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Con MockCsamHashService (siempre "limpio") el camino FLAGGED nunca se ejercita en dev/local
 * — este test lo cubre igual, con un CsamHashService falso que sí matchea, para no dejar esa
 * rama de la Fase 4 (strike + baneo + reporte NCMEC) sin probar.
 */
@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    @Mock
    CsamHashService csamHashService;
    @Mock
    ModerationLogRepository moderationLogRepository;
    @Mock
    CsamHashMatchRepository csamHashMatchRepository;
    @Mock
    NcmecReportClient ncmecReportClient;
    @Mock
    StrikeService strikeService;
    @Mock
    ClipRepository clipRepository;

    private ModerationService newService() {
        return new ModerationService(csamHashService, moderationLogRepository, csamHashMatchRepository,
                ncmecReportClient, strikeService, clipRepository, new ObjectMapper());
    }

    @Test
    void approvesWhenNoFrameMatches() throws Exception {
        ModerationService moderationService = newService();
        UUID clipId = UUID.randomUUID();
        Clip clip = new Clip(new User("owner@example.com", "hash", "Owner"), ClipSourceType.OWN_UPLOAD);
        when(clipRepository.getReferenceById(clipId)).thenReturn(clip);
        when(csamHashService.checkFrame(any(Path.class))).thenReturn(CsamHashService.FrameCheckResult.clean());

        List<ModerationService.FrameSample> frames = List.of(
                new ModerationService.FrameSample(Path.of("frame1.jpg"), 0),
                new ModerationService.FrameSample(Path.of("frame2.jpg"), 1000));

        ModerationService.Outcome outcome = moderationService.moderate(clipId, frames);

        assertThat(outcome).isEqualTo(ModerationService.Outcome.APPROVED);
        verify(moderationLogRepository).save(argThat(log -> log.getResult() == ModerationResult.CLEAN));
        verifyNoInteractions(strikeService, ncmecReportClient, csamHashMatchRepository);
    }

    @Test
    void rejectsBansTheOwnerAndReportsToNcmecOnAMatch() throws Exception {
        ModerationService moderationService = newService();
        UUID clipId = UUID.randomUUID();
        User owner = new User("owner@example.com", "hash", "Owner");
        Clip clip = new Clip(owner, ClipSourceType.OWN_UPLOAD);
        when(clipRepository.findByIdWithOwner(clipId)).thenReturn(Optional.of(clip));
        when(csamHashService.checkFrame(any(Path.class)))
                .thenReturn(new CsamHashService.FrameCheckResult(true, "deadbeef", "NCMEC_PDQ"));
        when(ncmecReportClient.report(eq(clipId), eq("NCMEC_PDQ"))).thenReturn("ncmec-123");

        List<ModerationService.FrameSample> frames = List.of(new ModerationService.FrameSample(Path.of("frame1.jpg"), 0));

        ModerationService.Outcome outcome = moderationService.moderate(clipId, frames);

        assertThat(outcome).isEqualTo(ModerationService.Outcome.REJECTED);
        verify(strikeService).recordCsamStrike(owner, null);
        verify(csamHashMatchRepository).save(any(CsamHashMatch.class));
        verify(ncmecReportClient).report(clipId, "NCMEC_PDQ");
        // FLAGGED + REPORTED_NCMEC: dos entradas de auditoría por el mismo hallazgo.
        verify(moderationLogRepository, times(2)).save(any(ModerationLog.class));
    }

    @Test
    void stopsCheckingFramesAfterTheFirstMatch() throws Exception {
        ModerationService moderationService = newService();
        UUID clipId = UUID.randomUUID();
        Clip clip = new Clip(new User("owner@example.com", "hash", "Owner"), ClipSourceType.OWN_UPLOAD);
        when(clipRepository.findByIdWithOwner(clipId)).thenReturn(Optional.of(clip));
        when(csamHashService.checkFrame(any(Path.class)))
                .thenReturn(new CsamHashService.FrameCheckResult(true, "hash1", "NCMEC_PDQ"));

        List<ModerationService.FrameSample> frames = List.of(
                new ModerationService.FrameSample(Path.of("frame1.jpg"), 0),
                new ModerationService.FrameSample(Path.of("frame2.jpg"), 1000),
                new ModerationService.FrameSample(Path.of("frame3.jpg"), 2000));

        moderationService.moderate(clipId, frames);

        verify(csamHashService, times(1)).checkFrame(any(Path.class));
    }
}
