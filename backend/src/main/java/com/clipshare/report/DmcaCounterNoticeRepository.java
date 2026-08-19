package com.clipshare.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DmcaCounterNoticeRepository extends JpaRepository<DmcaCounterNotice, UUID> {
}
