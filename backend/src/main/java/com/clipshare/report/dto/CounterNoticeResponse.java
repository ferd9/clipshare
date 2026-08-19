package com.clipshare.report.dto;

import com.clipshare.report.DmcaCounterNotice;

import java.time.Instant;
import java.util.UUID;

public record CounterNoticeResponse(UUID id, Instant restoreEligibleAt) {
    public static CounterNoticeResponse from(DmcaCounterNotice notice) {
        return new CounterNoticeResponse(notice.getId(), notice.getRestoreEligibleAt());
    }
}
